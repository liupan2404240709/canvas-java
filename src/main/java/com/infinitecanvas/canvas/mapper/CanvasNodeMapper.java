package com.infinitecanvas.canvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infinitecanvas.canvas.entity.CanvasNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface CanvasNodeMapper extends BaseMapper<CanvasNode> {

    /** 批量插入。一条 INSERT 拼多个 VALUES，比循环 insert 少 N-1 次网络往返。 */
    int insertBatch(@Param("list") List<CanvasNode> list);
}
