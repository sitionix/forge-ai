on run argv
  set cmd to item 1 of argv
  set sourceTty to my trimText(item 2 of argv)
  set hasTty to (sourceTty is not "")
  set foundWindow to missing value
  tell application "iTerm"
    if hasTty then
      repeat with w in windows
        repeat with t in tabs of w
          repeat with s in sessions of t
            try
              if my trimText(tty of s as text) is sourceTty then
                set foundWindow to w
                exit repeat
              end if
            end try
          end repeat
          if foundWindow is not missing value then exit repeat
        end repeat
        if foundWindow is not missing value then exit repeat
      end repeat
    end if
    if foundWindow is missing value then
      error "source terminal tty not found: " & sourceTty
    end if
    tell foundWindow
      set createdTab to (create tab with default profile)
      tell current session of createdTab to write text cmd
    end tell
    activate
  end tell
end run

on trimText(valueText)
  if valueText is missing value then
    return ""
  end if
  set cleaned to valueText as text
  repeat while cleaned begins with space or cleaned begins with tab or cleaned begins with return or cleaned begins with linefeed
    set cleaned to text 2 thru -1 of cleaned
  end repeat
  repeat while cleaned ends with space or cleaned ends with tab or cleaned ends with return or cleaned ends with linefeed
    set cleaned to text 1 thru -2 of cleaned
  end repeat
  return cleaned
end trimText
