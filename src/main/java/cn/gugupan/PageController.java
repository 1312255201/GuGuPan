package cn.gugupan;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class PageController {
    @GetMapping({"/", "/login", "/admin", "/admin/**", "/s/{token}", "/r/{token}"})
    String page() {
        return "forward:/index.html";
    }
}
