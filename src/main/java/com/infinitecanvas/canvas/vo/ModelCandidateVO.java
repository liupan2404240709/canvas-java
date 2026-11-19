package com.infinitecanvas.canvas.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 从上游拉回来的一个候选模型。
 *
 * <p>比光返回一个字符串多两样东西，都是为了让前端"勾选导入"这一步能直接用：
 * <ul>
 *   <li>{@code capability} 是后端猜的初始分类。上游 {@code /models} 只给名字，
 *       让前端自己猜就等于每个客户端都要重写一遍关键字表；</li>
 *   <li>{@code exists} 表示这个模型已经在渠道里了。前端据此做人去重和"已添加"灰态，
 *       不用先把已有列表拉下来自己比对。</li>
 * </ul>
 */
@Schema(description = "候选模型")
public record ModelCandidateVO(

        @Schema(description = "上游模型名")
        String name,

        @Schema(description = "后端猜测的能力分类，用户可改")
        String capability,

        @Schema(description = "渠道里是否已有同名模型")
        boolean exists
) {
}
