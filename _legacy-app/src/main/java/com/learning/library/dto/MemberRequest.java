package com.learning.library.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record MemberRequest(
    @NotBlank(message = "Member ID cannot be blank")
    String memberId,

    @NotBlank(message = "Name cannot be blank")
    String name,

    @Email(message = "Email must be valid")
    @NotBlank(message = "Email cannot be blank")
    String email
) {}
