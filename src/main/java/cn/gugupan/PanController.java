package cn.gugupan;

import static cn.gugupan.Models.*;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class PanController {
    private final PanService service;
    private final RequestLimiter limiter;
    private final SessionRegistry sessions;

    public PanController(PanService service, RequestLimiter limiter, SessionRegistry sessions) {
        this.service = service;
        this.limiter = limiter;
        this.sessions = sessions;
    }

    @GetMapping("/auth/session")
    Map<String, Object> session(Authentication authentication, CsrfToken csrfToken) {
        boolean authenticated = authentication != null && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
        return Map.of("authenticated", authenticated,
                "username", authenticated ? authentication.getName() : "",
                "csrfToken", csrfToken.getToken());
    }

    @GetMapping("/health")
    Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @GetMapping("/admin/stats")
    Map<String, Object> stats() {
        return service.stats();
    }

    @GetMapping("/admin/settings")
    Settings settings() {
        return service.settings();
    }

    @PutMapping("/admin/settings")
    Settings updateSettings(@Valid @RequestBody SettingsRequest request) {
        return service.updateSettings(request);
    }

    @PostMapping("/admin/password")
    Map<String, Boolean> changePassword(@Valid @RequestBody ChangePasswordRequest request,
                                       HttpServletRequest http) throws IOException {
        limiter.check("password:" + http.getRemoteAddr(), 20);
        service.changePassword(request);
        for (Object principal : sessions.getAllPrincipals()) {
            sessions.getAllSessions(principal, false).forEach(session -> session.expireNow());
        }
        http.getSession().invalidate();
        return Map.of("ok", true);
    }

    @GetMapping("/admin/files")
    List<FileItem> files() {
        return service.files();
    }

    @PostMapping(value = "/admin/files", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    FileItem upload(@RequestParam String name, @RequestParam(required = false) Integer days,
                    HttpServletRequest request) throws IOException {
        return service.upload(request.getInputStream(), name, request.getContentLengthLong(), days, null);
    }

    @GetMapping("/admin/files/{id}/download")
    ResponseEntity<Resource> download(@PathVariable String id) {
        return downloadResponse(service.availableFile(id));
    }

    @DeleteMapping("/admin/files/{id}")
    Map<String, Boolean> delete(@PathVariable String id) throws IOException {
        service.deleteFile(id);
        return Map.of("ok", true);
    }

    @PatchMapping("/admin/files/{id}")
    FileItem extend(@PathVariable String id, @Valid @RequestBody RetentionRequest request) {
        return service.extendFile(id, request.days());
    }

    @GetMapping("/admin/links")
    List<LinkItem> links() {
        return service.links();
    }

    @PostMapping("/admin/shares")
    LinkItem share(@Valid @RequestBody ShareRequest request) {
        return service.createShare(request);
    }

    @PostMapping("/admin/inboxes")
    LinkItem inbox(@Valid @RequestBody InboxRequest request) {
        return service.createInbox(request);
    }

    @DeleteMapping("/admin/links/{token}")
    Map<String, Boolean> revoke(@PathVariable String token) {
        service.revokeLink(token);
        return Map.of("ok", true);
    }

    @PostMapping("/public/resolve")
    Map<String, String> resolve(@Valid @RequestBody CodeRequest request, HttpServletRequest http) {
        limiter.check("resolve:" + http.getRemoteAddr(), 40);
        LinkItem link = service.resolveCode(request.code());
        return Map.of("path", ("SHARE".equals(link.kind()) ? "/s/" : "/r/") + link.token());
    }

    @GetMapping("/public/links/{token}")
    Map<String, Object> publicLink(@PathVariable String token, HttpSession session) {
        LinkItem link = service.activeLink(token, null);
        boolean locked = link.passwordProtected() && !hasGrant(session, token);
        Map<String, Object> result = new HashMap<>();
        result.put("link", link);
        result.put("locked", locked);
        result.put("files", !locked && "SHARE".equals(link.kind()) ? service.shareFiles(token) : List.of());
        if (!locked && "INBOX".equals(link.kind())) {
            result.put("usage", service.inboxUsage(token));
            result.put("systemMaxFileBytes", service.settings().maxFileBytes());
        }
        return result;
    }

    @PostMapping("/public/links/{token}/unlock")
    Map<String, Boolean> unlock(@PathVariable String token, @Valid @RequestBody PasswordRequest request,
                               HttpServletRequest http) {
        limiter.check("unlock:" + http.getRemoteAddr(), 30);
        service.activeLink(token, null);
        if (!service.verifyLinkPassword(token, request.password())) {
            throw new ApiException(403, "访问密码不正确");
        }
        HttpSession session = http.getSession();
        synchronized (session) {
            Set<String> grants = grants(session);
            if (grants.size() >= 64) {
                grants.remove(grants.iterator().next());
            }
            grants.add(token);
            session.setAttribute("linkGrants", grants);
        }
        return Map.of("ok", true);
    }

    @GetMapping("/public/links/{token}/files/{id}")
    ResponseEntity<Resource> publicDownload(@PathVariable String token, @PathVariable String id,
                                             HttpSession session) {
        LinkItem link = service.activeLink(token, "SHARE");
        requireGrant(link, session);
        boolean member = service.shareFiles(token).stream().anyMatch(file -> file.id().equals(id));
        if (!member) {
            throw new ApiException(404, "该分享中没有此文件");
        }
        return downloadResponse(service.availableFile(id));
    }

    @PostMapping(value = "/public/links/{token}/files", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    Map<String, Object> publicUpload(@PathVariable String token, @RequestParam String name,
                                     HttpServletRequest request) throws IOException {
        limiter.check("upload:" + request.getRemoteAddr(), 100);
        LinkItem link = service.activeLink(token, "INBOX");
        requireGrant(link, request.getSession());
        FileItem file = service.upload(request.getInputStream(), name, request.getContentLengthLong(), null, token);
        // Visitors get a receipt. They cannot use it to list or download inbox contents.
        return Map.of("name", file.name(), "size", file.size(), "received", true);
    }

    private ResponseEntity<Resource> downloadResponse(FileItem file) {
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(file.size())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(service.filePath(file.id())));
    }

    private void requireGrant(LinkItem link, HttpSession session) {
        if (link.passwordProtected() && !hasGrant(session, link.token())) {
            throw new ApiException(403, "请先输入访问密码");
        }
    }

    private boolean hasGrant(HttpSession session, String token) {
        synchronized (session) {
            return grants(session).contains(token);
        }
    }

    private Set<String> grants(HttpSession session) {
        Object attribute = session.getAttribute("linkGrants");
        if (attribute instanceof Set<?> existing) {
            Set<String> copy = new LinkedHashSet<>();
            existing.forEach(item -> copy.add((String) item));
            return copy;
        }
        return new LinkedHashSet<>();
    }
}
