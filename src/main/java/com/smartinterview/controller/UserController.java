package com.smartinterview.controller;

import com.smartinterview.common.result.Result;
import com.smartinterview.dto.CodeLoginDTO;
import com.smartinterview.dto.PasswordLoginDTO;
import com.smartinterview.dto.RegisterDTO;
import com.smartinterview.dto.UpdateUserDTO;

import com.smartinterview.service.UserService;
import com.smartinterview.vo.UserVO;
import dev.langchain4j.service.TokenStream;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

@RestController
@Slf4j
@RequestMapping("user")
@Tag(name = "用户中心")
public class UserController {

    @Autowired
    private UserService userService;

    @Operation(summary = "发送验证码")
    @PostMapping("code")
    public Result sendCode(@RequestParam("phone") String phone) {
        String code = userService.sendMessage(phone);
        return Result.success(code);

    }

    @Operation(summary = "验证码登录")
    @PostMapping("code/login")
    public Result CodeLogin(@RequestBody CodeLoginDTO codeLoginDTO) {
        String token = userService.codeLogin(codeLoginDTO);
        return Result.success(token);
    }

    @Operation(summary = "密码登录")
    @PostMapping("password/login")
    public Result passwordLogin(@RequestBody PasswordLoginDTO passwordLoginDTO) {
        String token = userService.passwordLogin(passwordLoginDTO);
        return Result.success(token);
    }

    @Operation(summary = "用户注册")
    @PostMapping("register")
    public Result register(@RequestBody RegisterDTO registerDTO) {
        userService.register(registerDTO);
        return Result.success();
    }

    @Operation(summary = "用户退出")
    @PostMapping("logout")
    public Result logout(HttpServletRequest request) {
        String token = request.getHeader("Authorization");
        userService.logout(token);
        return Result.success();
    }

    @Operation(summary = "查询用户信息")
    @GetMapping("me")
    public Result getUser() {
        UserVO userVO = userService.queryUser();
        return Result.success(userVO);
    }

    @Operation(summary = "上传头像")
    @PostMapping("avatar")
    public Result uploadAvatar(@RequestParam(defaultValue = "file") MultipartFile file) {
        String url = userService.uploadAvatar(file);
        return Result.success(url);
    }

    @Operation(summary = "修改用户信息")
    @PutMapping("/update")
    public Result updateUser(@RequestBody UpdateUserDTO userDTO, HttpServletRequest request) {
        userService.updateUser(userDTO, request);
        return Result.success();
    }
}