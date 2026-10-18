package com.uniblox.checkout.api;
import org.springframework.http.*; import org.springframework.web.bind.annotation.*;
@RestControllerAdvice class ErrorHandler { @ExceptionHandler(ApiException.class) ProblemDetail handle(ApiException e){ ProblemDetail p=ProblemDetail.forStatusAndDetail(e.status,e.getMessage()); p.setProperty("code",e.code); return p;} }
