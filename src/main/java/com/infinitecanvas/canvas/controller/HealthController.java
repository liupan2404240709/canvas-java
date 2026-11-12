package com.infinitecanvas.canvas.controller;

import com.infinitecanvas.canvas.common.api.Result;
import com.infinitecanvas.canvas.common.constant.ApiPath;
import com.infinitecanvas.canvas.service.HealthService;
import com.infinitecanvas.canvas.vo.HealthVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "系统")
@RestController
@RequestMapping(ApiPath.V1 + "/health")
@RequiredArgsConstructor
public class HealthController {

    private final HealthService healthService;

    @Operation(summary = "健康检查", description = "依次探测 MySQL 与 Redis，并返回各自耗时")
    @GetMapping
    public Result<HealthVO> health() {
        return Result.ok(healthService.check());
    }
}
