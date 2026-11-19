package com.infinitecanvas.canvas.controller;

import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.constant.ApiPath;
import com.infinitecanvas.canvas.security.SecurityUtils;
import com.infinitecanvas.canvas.service.ChannelService;
import com.infinitecanvas.canvas.vo.ModelOptionVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 跨渠道的可用模型。
 *
 * <p>为什么不放在 {@code /channels} 下面：前端下拉框要的是"我所有能用的生图模型"，
 * 而不是"先选渠道再选模型"。挂在渠道下就意味着前端必须先把渠道全拉下来、
 * 自己拍平、自己过滤停用的渠道——那是三次判断，而这里是一个 {@code capability} 参数。
 *
 * <p>停用的渠道和停用的模型都不会出现在结果里，所以这个接口的返回值可以直接当成
 * "当前真正可用的模型清单"。
 */
@Tag(name = "模型", description = "跨渠道的可用模型列表，供前端下拉框使用")
@RestController
@RequestMapping(ApiPath.MODELS)
public class ModelController {

    private final ChannelService channelService;

    public ModelController(ChannelService channelService) {
        this.channelService = channelService;
    }

    @Operation(summary = "可用模型", description = "只返回启用渠道下的启用模型；id 格式为 channelKey::modelName")
    @GetMapping
    public Result<List<ModelOptionVO>> list(
            @Parameter(description = "按能力过滤：image / video / text / audio；不传返回全部")
            @RequestParam(required = false) String capability) {
        return Result.ok(channelService.models(SecurityUtils.currentUserId(), capability));
    }
}
