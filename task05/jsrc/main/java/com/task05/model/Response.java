package com.task05.dto;

import lombok.*;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Response {
    private Integer statusCode;
    private Event event;

}