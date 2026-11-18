package com.infinitecanvas.canvas.infra.prompt;

import java.util.List;

/**
 * 内置提示词源。和前端 {@code prompt-source-presets.ts} 里的
 * {@code DEFAULT_PROMPT_SOURCES} 一一对应（7 个公开的 GPT-Image 提示词仓库）。
 *
 * <p>为什么写死在代码里而不是放数据库：
 * <ul>
 *   <li>它们是"产品自带的默认值"，跟着代码版本走，不需要运营后台去改；</li>
 *   <li>放数据库就要有初始化脚本、要考虑老用户升级时补数据，成本更高；</li>
 *   <li>true 的 {@code built_in} 标记让用户不能删除，只能停用，避免"默认源被删光"。</li>
 * </ul>
 *
 * <p>每个用户首次访问时会被播种成自己的 7 行（{@code ensureBuiltIns}），
 * 这样启用/停用、同步状态都是用户自己的，互不影响。
 */
public final class BuiltInPromptSources {

    /** 上游 registry 的 dist 目录。id 与文件名一一对应。 */
    private static final String REGISTRY_BASE = "https://raw.githubusercontent.com/yukkcat/image-prompts/main/dist/sources";

    private BuiltInPromptSources() {
    }

    public record BuiltIn(String key, String name, String homepage) {

        public String url() {
            return REGISTRY_BASE + "/" + key + ".json";
        }
    }

    public static final List<BuiltIn> ALL = List.of(
            new BuiltIn("banana-prompt-quicker", "Banana Prompt Quicker", "https://glidea.github.io/banana-prompt-quicker/"),
            new BuiltIn("davidwu-gpt-image2-prompts", "DavidWu GPT Image 2", "https://github.com/davidwuw0811-boop/awesome-gpt-image2-prompts"),
            new BuiltIn("freestylefly-gpt-image-2", "Freestylefly GPT Image 2", "https://github.com/freestylefly/awesome-gpt-image-2"),
            new BuiltIn("awesome-gpt-image", "Awesome GPT Image", "https://github.com/ZeroLu/awesome-gpt-image"),
            new BuiltIn("awesome-gpt4o-image-prompts", "Awesome GPT-4o", "https://github.com/ImgEdify/Awesome-GPT4o-Image-Prompts"),
            new BuiltIn("youmind-gpt-image-2", "YouMind GPT Image 2", "https://github.com/YouMind-OpenLab/awesome-gpt-image-2"),
            new BuiltIn("youmind-nano-banana-pro", "YouMind Nano Banana Pro", "https://github.com/YouMind-OpenLab/awesome-nano-banana-pro-prompts")
    );
}
