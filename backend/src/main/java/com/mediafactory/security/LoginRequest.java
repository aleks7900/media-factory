package com.mediafactory.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record LoginRequest(
    @NotBlank(message = "Username or email is required")
    @Size(max = 255)
    String username,

    @NotBlank(message = "Password is required")
    @Size(max = 255)
    String password
) {}
