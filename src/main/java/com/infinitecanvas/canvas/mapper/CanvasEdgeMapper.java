package com.infinitecanvas.canvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infinitecanvas.canvas.entity.CanvasEdge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CanvasEdgeMapper extends BaseMapper<CanvasEdge> {

    int insertBatch(@Param("list") List<CanvasEdge> list);
}
