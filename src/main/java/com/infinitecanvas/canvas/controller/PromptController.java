package com.infinitecanvas.canvas.controller;

import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.constant.ApiPath;
import com.infinitecanvas.canvas.security.SecurityUtils;
import com.infinitecanvas.canvas.service.PromptService;
import com.infinitecanvas.canvas.vo.PromptListVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "提示词库", description = "跨源查询提示词：关键字、标签、分栏筛选 + 分页")
@RestController
@RequestMapping(ApiPath.PROMPTS)
public class PromptController {

    private final PromptService promptService;

    public PromptController(PromptService promptService) {
        this.promptService = promptService;
    }

    @Operation(summary = "提示词列表",
            description = "标签可重复传多个（tag=a&tag=b），语义是「或」；category 传源名称，不传或 all 表示全部")
    @GetMapping
    public Result<PromptListVO> list(
            @Parameter(description = "关键字，匹配标题/正文/说明")
            @RequestParam(required = false) String keyword,

            @Parameter(description = "标签，可重复传多个")
            @RequestParam(required = false) List<String> tag,

            @Parameter(description = "分栏（源名称）")
            @RequestParam(required = false) String category,

            @Parameter(description = "页码，从 1 开始")
            @RequestParam(defaultValue = "1") long page,

            @Parameter(description = "每页条数，最大 100")
            @RequestParam(defaultValue = "20") long pageSize) {
        return Result.ok(promptService.list(SecurityUtils.currentUserId(), keyword, tag, category, page, pageSize));
    }
}
