package com.example.tuanjian.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 供应商交场两道签字的请求：现场对接人到场记录 / 科室复核人复核，
 * 都必须留下签字人姓名（系统不设登录角色，签字人由操作人在页面上留名）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HandoffSignRequest {

    @NotBlank(message = "签字人不能为空")
    private String signerName;

}
