package com.infinitecanvas.canvas.config;

import com.infinitecanvas.canvas.infra.prompt.PromptProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * 拉取提示词源用的 HTTP 客户端。
 *
 * <p>单独建一个 named bean（{@code promptRestClient}）而不是注入默认的
 * {@code RestClient.Builder}：同步任务的超时策略和业务接口完全不同，
 * 混用默认值会出现"一个源卡住 10 分钟"这种事故。
 *
 * <p>用 {@link SimpleClientHttpRequestFactory}（底层是 HttpURLConnection）而不是
 * WebClient：这里是同步拉取，一个源一份 JSON，没有背压和流式需求，
 * 引入 Reactor 只会让调用链变复杂。
 */
@Configuration
@EnableConfigurationProperties(PromptProperties.class)
public class PromptHttpConfig {

    @Bean
    public RestClient promptRestClient(RestClient.Builder builder, PromptProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) properties.getConnectTimeout().toMillis());
        factory.setReadTimeout((int) properties.getReadTimeout().toMillis());
        return builder
                .requestFactory(factory)
                // 不在客户端这里抛 4xx/5xx 异常：状态码在 PromptFetcher 里统一判断，
                // 这样 304 和 404 的处理逻辑能放在一起看。
                // 谓词永远返回 false = "任何状态码都不当成异常"，真正的判断在 exchange 里做。
                .defaultStatusHandler(status -> false, (request, response) -> {
                })
                .build();
    }
}
