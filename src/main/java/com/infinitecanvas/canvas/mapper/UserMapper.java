package com.infinitecanvas.canvas.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.infinitecanvas.canvas.entity.User;

/**
 * BaseMapper 已经提供 insert / selectById / selectOne / update 等常用方法，
 * 这里暂时不需要写任何 SQL。复杂查询再各自补充注解或 XML。
 */
public interface UserMapper extends BaseMapper<User> {
}
