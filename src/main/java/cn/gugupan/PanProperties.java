package cn.gugupan;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("gugupan")
public record PanProperties(Path dataDir, String initialUsername, String initialPassword) {
}
