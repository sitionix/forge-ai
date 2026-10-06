package com.sitionix.forgeai.api.llm;

import jakarta.servlet.http.*;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.stereotype.Component;

/** Opaque ownership is issued and recognized by this Nexus, never accepted from a browser body. */
@Component
public class LlmAuthorizationBrowserGuard {
    public static final String COOKIE="ForgeLlmBrowser";
    public static final String BINDING_ATTRIBUTE="forgeLlmBinding";
    private final LlmAuthorizationBrowserProperties properties;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Instant> issued=new HashMap<>();
    public LlmAuthorizationBrowserGuard(LlmAuthorizationBrowserProperties properties){this.properties=properties;}

    public void require(HttpServletRequest request,HttpServletResponse response,boolean bootstrap) {
        var origin=single(request,HttpHeaders.ORIGIN);var fetch=single(request,"Sec-Fetch-Site");
        if(!actualOrigin(request) || origin!=null && !properties.browserOrigin().toString().equals(origin)
                || fetch!=null && !"same-origin".equals(fetch))throw new Denied();
        boolean mutation=!"GET".equals(request.getMethod());
        if(mutation && (origin==null || !json(request.getContentType())))throw new Denied();
        var cookies=request.getCookies()==null ? List.<Cookie>of() : Arrays.stream(request.getCookies()).filter(c->COOKIE.equals(c.getName())).toList();
        if(cookies.size()>1)throw new Denied();
        String binding=cookies.isEmpty()?null:cookies.get(0).getValue();
        synchronized(issued){
            Instant now=Instant.now();issued.entrySet().removeIf(entry->!entry.getValue().isAfter(now));
            if(binding==null || !issued.containsKey(binding)) {
                if(!bootstrap || mutation || origin==null && !"same-origin".equals(fetch))throw new Denied();
                if(issued.size()>=4096)throw new com.sitionix.forgeai.domain.model.llm.LlmAuthorizationFailure("CODEX_AUTH_UNAVAILABLE");
                byte[] bytes=new byte[32];random.nextBytes(bytes);binding=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                issued.put(binding,now.plus(properties.bindingTtl()));
                String context=request.getContextPath().isEmpty()?"/fgaisox":request.getContextPath();
                response.addHeader(HttpHeaders.SET_COOKIE,ResponseCookie.from(COOKIE,binding).httpOnly(true).secure(request.isSecure()).sameSite("Lax")
                        .path(context+ForgeAiLlmAuthorizationController.PREFIX).maxAge(properties.bindingTtl()).build().toString());
            }
        }
        request.setAttribute(BINDING_ATTRIBUTE,binding);
    }
    private boolean actualOrigin(HttpServletRequest request){
        var configured=properties.browserOrigin();var host=single(request,HttpHeaders.HOST);
        try {
            if(host==null)return false;
            var actual=URI.create(request.getScheme()+"://"+host);
            return actual.getRawUserInfo()==null && actual.getRawQuery()==null && actual.getRawFragment()==null && actual.getPath().isEmpty()
                    && configured.getScheme().equals(request.getScheme()) && configured.getHost().equalsIgnoreCase(request.getServerName())
                    && port(configured)==request.getServerPort() && configured.getHost().equalsIgnoreCase(actual.getHost()) && port(configured)==port(actual);
        } catch(RuntimeException failure){return false;}
    }
    private static int port(URI uri){return uri.getPort()<0 ? "https".equals(uri.getScheme())?443:80 : uri.getPort();}
    private static String single(HttpServletRequest request,String name){
        var values=Collections.list(request.getHeaders(name));if(values.size()>1)throw new Denied();return values.isEmpty()?null:values.get(0);
    }
    private static boolean json(String type){try{if(type==null)return false;var media=MediaType.parseMediaType(type);return "application".equals(media.getType()) && "json".equals(media.getSubtype());}catch(RuntimeException failure){return false;}}
    public static final class Denied extends RuntimeException {public Denied(){super("LLM_BROWSER_DENIED");}}
}
