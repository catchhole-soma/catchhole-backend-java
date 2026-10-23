package org.monitoring.catchholebackend.global.config.analysisnotification;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("analysis.notifications")
public class AnalysisNotificationProperties {
    private String discordWebhookUrl = "";
    private String environment = "local";

    public String getDiscordWebhookUrl() { return discordWebhookUrl; }
    public void setDiscordWebhookUrl(String value) { discordWebhookUrl = value; }
    public String getEnvironment() { return environment; }
    public void setEnvironment(String value) { environment = value; }

    public URI webhookUri() {
        // 오류에도 토큰이 포함된 URL을 출력하지 않는다. Redirect로 다른 호스트에 전달하지 않는다.
        try {
            URI uri = URI.create(discordWebhookUrl);
            if (!"https".equals(uri.getScheme()) || !"discord.com".equals(uri.getHost())
                    || uri.getPort() != -1 || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null
                    || !uri.getRawPath().matches("/api(?:/v[0-9]+)?/webhooks/[0-9]+/[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            return URI.create(discordWebhookUrl + "?wait=true");
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("분석 알림용 Discord 웹훅 설정이 올바르지 않습니다.");
        }
    }
}
