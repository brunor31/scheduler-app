package com.greentech.scheduler_app.adapter.in.security;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.*;
import java.util.regex.Pattern;

/** Runs after Spring Security, before deserialization; never stores request payloads in logs. */
@Component
@Order(0)
public class RequestLimitsFilter extends OncePerRequestFilter {
    private static final Pattern TENANT=Pattern.compile("^/v1/tenants/([A-Za-z0-9][A-Za-z0-9._-]{0,127})/schedules(?:/.*)?$");
    private final JdbcClient jdbc;
    private final int perSecond;
    public RequestLimitsFilter(JdbcClient jdbc,@Value("${scheduler.admission.requests-per-second-per-tenant:100}") int perSecond) {
        if(perSecond<1) throw new IllegalArgumentException("request rate must be positive");
        this.jdbc=jdbc; this.perSecond=perSecond;
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain) throws ServletException,IOException {
        if(!request.getRequestURI().startsWith("/v1/")) { chain.doFilter(request,response); return; }
        var match=TENANT.matcher(request.getRequestURI());
        if(!match.matches()) { problem(response,400,"Invalid tenant path"); return; }
        int accepted;
        try {
            accepted=jdbc.sql("""
                INSERT INTO tenant_request_window(tenant_id,window_start,requests)
                VALUES (:tenant,date_trunc('second',clock_timestamp()),1)
                ON CONFLICT(tenant_id) DO UPDATE SET
                    window_start=date_trunc('second',clock_timestamp()),
                    requests=CASE WHEN tenant_request_window.window_start=date_trunc('second',clock_timestamp())
                        THEN tenant_request_window.requests+1 ELSE 1 END
                WHERE tenant_request_window.window_start<>date_trunc('second',clock_timestamp()) OR tenant_request_window.requests<:limit
                """).param("tenant",match.group(1)).param("limit",perSecond).update();
        } catch(org.springframework.dao.DataAccessException e) { problem(response,503,"Persistence temporarily unavailable"); return; }
        if(accepted==0) { response.setHeader("Retry-After","1"); problem(response,429,"Tenant request rate exceeded"); return; }
        if(!"POST".equals(request.getMethod()) && !"PUT".equals(request.getMethod())) { chain.doFilter(request,response); return; }
        int max=80*1024;
        if(request.getContentLengthLong()>max) { problem(response,413,"Request exceeds 80 KiB"); return; }
        byte[] body=request.getInputStream().readNBytes(max+1);
        if(body.length>max) { problem(response,413,"Request exceeds 80 KiB"); return; }
        chain.doFilter(new HttpServletRequestWrapper(request) {
            @Override public ServletInputStream getInputStream() {
                ByteArrayInputStream input=new ByteArrayInputStream(body);
                return new ServletInputStream() {
                    public int read() { return input.read(); }
                    public int read(byte[] b,int offset,int length) { return input.read(b,offset,length); }
                    public boolean isFinished() { return input.available()==0; }
                    public boolean isReady() { return true; }
                    public void setReadListener(ReadListener listener) { throw new UnsupportedOperationException("synchronous request only"); }
                };
            }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(getInputStream(),java.nio.charset.StandardCharsets.UTF_8)); }
        },response);
    }
    private static void problem(HttpServletResponse response,int status,String detail) throws IOException {
        response.setStatus(status); response.setContentType("application/problem+json");
        response.getWriter().write("{\"status\":"+status+",\"detail\":\""+detail+"\"}");
    }
}
