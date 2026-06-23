package com.example.aispringvideo.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.example.aispringvideo.entity.User;

public interface UserService extends IService<User> {
    User findByUsername(String username);
}
