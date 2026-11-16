package com.infinitecanvas.canvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infinitecanvas.canvas.entity.CanvasProject;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 画布项目 Mapper。常规 CRUD 由 BaseMapper 提供，只有两件事必须手写 SQL：
 * 1. 查回收站——@TableLogic 会自动给所有查询加上 deleted = 0，查已删数据必须绕开它；
 * 2. 恢复——逻辑删除后 updateById 也会被自动加上 deleted = 0 条件，永远更新不到。
 */
@Mapper
public interface CanvasProjectMapper extends BaseMapper<CanvasProject> {

    List<CanvasProject> selectTrash(@Param("userId") Long userId);

    int restore(@Param("id") Long id, @Param("userId") Long userId);
}
