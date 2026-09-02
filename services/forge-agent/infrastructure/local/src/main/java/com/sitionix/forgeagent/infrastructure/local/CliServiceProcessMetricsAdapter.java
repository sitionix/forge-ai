package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.exception.InfrastructureExecutionException;
import com.sitionix.forgeagent.domain.model.*;
import com.sitionix.forgeagent.domain.port.ServiceProcessMetricsPort;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class CliServiceProcessMetricsAdapter implements ServiceProcessMetricsPort {
  static final String PROCESS_PROBE = """
      unit="$1"
      proc_root="${FORGE_PROC_ROOT:-/proc}"
      cgroup_root="${FORGE_CGROUP_ROOT:-/sys/fs/cgroup}"
      properties="$(systemctl show --property=LoadState,ActiveState,SubState,ControlGroup "$unit")" || exit $?
      load="$(printf '%s\n' "$properties" | sed -n 's/^LoadState=//p')"
      active="$(printf '%s\n' "$properties" | sed -n 's/^ActiveState=//p')"
      sub="$(printf '%s\n' "$properties" | sed -n 's/^SubState=//p')"
      group="$(printf '%s\n' "$properties" | sed -n 's/^ControlGroup=//p')"
      [ "$load" = loaded ] && [ "$active" = active ] && [ "$sub" = running ] || exit 44
      if [ -z "$group" ]; then
        printf 'FORGE_HOST_TICKS\t0\t1\n'
        printf 'FORGE_SAMPLED_AT_NANOS\t%s\n' "$(date +%s%N)"
        exit 0
      fi
      case "$group" in /*) ;; *) exit 45 ;; esac
      root="$cgroup_root$group"
      [ -d "$root" ] || exit 45
      work="$(mktemp -d)" || exit $?
      trap 'rm -rf "$work"' EXIT HUP INT TERM
      pids() {
        find "$root" -type f -name cgroup.procs -print > "$work/cgroup-files" || return 1
        [ -s "$work/cgroup-files" ] || return 1
        : > "$work/pids-raw"
        while IFS= read -r file; do cat "$file" >> "$work/pids-raw" || return 1; done < "$work/cgroup-files"
        sort -nu "$work/pids-raw"
      }
      total_ticks() { awk '/^cpu / { total=0; for(i=2;i<=NF;i++) total+=$i; print total; exit }' "$proc_root/stat"; }
      process_ticks() {
        line="$(cat "$proc_root/$1/stat" 2>/dev/null)" || return 1
        rest="${line##*) }"
        set -- $rest
        [ -n "${12}" ] && [ -n "${13}" ] || return 1
        printf '%s\n' "$(( ${12} + ${13} ))"
      }
      before_host="$(total_ticks)" || exit $?
      pids > "$work/before-pids" || exit $?
      while IFS= read -r pid; do
        ticks="$(process_ticks "$pid")" || ticks=-
        printf '%s\t%s\n' "$pid" "$ticks"
      done < "$work/before-pids" > "$work/before"
      sleep 0.2
      after_host="$(total_ticks)" || exit $?
      pids > "$work/after-pids" || exit $?
      printf 'FORGE_HOST_TICKS\t%s\t%s\n' "$before_host" "$after_host"
      while IFS= read -r pid; do
        before="$(awk -v p="$pid" '$1 == p { print $2; exit }' "$work/before")"
        [ -n "$before" ] || before=-
        after="$(process_ticks "$pid")" || after=-
        stat="$(cat "$proc_root/$pid/stat" 2>/dev/null)" || stat=
        if [ -n "$stat" ]; then
          name="${stat%)*}"; name="${name#*(}"
          encoded="$(printf '%s' "$name" | base64 | tr -d '\n')"
          rest="${stat##*) }"; set -- $rest; threads="${18}"
          [ -n "$threads" ] || threads=-
        else encoded=-; threads=-; fi
        rss="$(awk '/^VmRSS:/ { print $2; found=1; exit } END { if (!found) print "-" }' "$proc_root/$pid/status" 2>/dev/null)"
        [ -n "$rss" ] || rss=-
        printf 'FORGE_PROCESS\t%s\t%s\t%s\t%s\t%s\t%s\n' "$pid" "$encoded" "$before" "$after" "$rss" "$threads"
      done < "$work/after-pids"
      printf 'FORGE_SAMPLED_AT_NANOS\t%s\n' "$(date +%s%N)"
      """;

  private final TypedProcessExecutor executor;
  public CliServiceProcessMetricsAdapter(TypedProcessExecutor executor) { this.executor = executor; }

  @Override
  public ServiceProcessMetricsSnapshot collect(SshConnection connection, String unit, ProcessMetricsSort sort) {
    Long hostBefore = null, hostAfter = null;
    Instant sampledAt = null;
    var raw = new ArrayList<RawProcess>();
    var command = RemoteShellCommand.ssh(connection,
        List.of("sh", "-c", PROCESS_PROBE, "forge-process-probe", unit));
    for (String line : executor.output(command, null, connection)) {
      String[] fields = line.split("\\t", -1);
      if (fields.length == 3 && fields[0].equals("FORGE_HOST_TICKS")) {
        hostBefore = number(fields[1]); hostAfter = number(fields[2]);
      } else if (fields.length == 7 && fields[0].equals("FORGE_PROCESS")) {
        Long pid = number(fields[1]);
        if (pid == null || pid <= 0) throw malformed();
        raw.add(new RawProcess(pid, text(fields[2]), number(fields[3]), number(fields[4]),
            number(fields[5]), number(fields[6])));
      } else if (fields.length == 2 && fields[0].equals("FORGE_SAMPLED_AT_NANOS")) {
        sampledAt = instant(fields[1]);
      } else if (!line.isBlank()) throw malformed();
    }
    if (hostBefore == null || hostAfter == null || sampledAt == null) throw malformed();
    if (hostBefore < 0 || hostAfter < 0) throw malformed();
    final long hostDelta;
    try { hostDelta = Math.subtractExact(hostAfter, hostBefore); }
    catch (ArithmeticException ignored) { throw malformed(); }
    var processes = raw.stream().map(p -> new ServiceProcessMetrics(p.pid, p.name,
        percent(p.before, p.after, hostDelta), bytes(p.rssKiB), positive(p.threads))).toList();
    Comparator<ServiceProcessMetrics> comparator = sort == ProcessMetricsSort.RAM
        ? nullableDescending(ServiceProcessMetrics::rssBytes)
        : nullableDescending(ServiceProcessMetrics::cpuPercent);
    processes = processes.stream().sorted(comparator.thenComparingLong(ServiceProcessMetrics::pid))
        .limit(5).toList();
    return new ServiceProcessMetricsSnapshot(unit, sort, sampledAt, processes);
  }

  private <T extends Comparable<T>> Comparator<ServiceProcessMetrics> nullableDescending(
      java.util.function.Function<ServiceProcessMetrics, T> value) {
    return Comparator.comparing(value, Comparator.nullsLast(Comparator.reverseOrder()));
  }
  private Double percent(Long before, Long after, long hostDelta) {
    if (before == null || after == null || before < 0 || after < 0 || after < before || hostDelta <= 0)
      return null;
    try { return Math.subtractExact(after, before) * 100.0 / hostDelta; }
    catch (ArithmeticException ignored) { return null; }
  }
  private Long bytes(Long kib) {
    if (kib == null || kib < 0) return null;
    try { return Math.multiplyExact(kib, 1024L); } catch (ArithmeticException ignored) { return null; }
  }
  private Long positive(Long value) { return value != null && value >= 0 ? value : null; }
  private Long number(String value) {
    try { return value == null || value.isBlank() || value.equals("-") ? null : Long.valueOf(value); }
    catch (NumberFormatException ignored) { return null; }
  }
  private String text(String encoded) {
    if (encoded == null || encoded.equals("-") || encoded.isBlank()) return null;
    try { return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8); }
    catch (IllegalArgumentException ignored) { return null; }
  }
  private Instant instant(String value) {
    Long nanos = number(value);
    return nanos == null || nanos < 0 ? null
        : Instant.ofEpochSecond(nanos / 1_000_000_000L, nanos % 1_000_000_000L);
  }
  private InfrastructureExecutionException malformed() {
    return new InfrastructureExecutionException("SERVICE_PROCESS_METRICS_INVALID",
        "Remote service process metrics response is invalid");
  }
  private record RawProcess(long pid, String name, Long before, Long after, Long rssKiB, Long threads) {}
}
