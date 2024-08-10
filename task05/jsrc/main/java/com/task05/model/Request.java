package com.task05.dto;


import lombok.*;

import java.util.Map;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Request {
    private int principalId;
    private Map<String, String> content;
}