package com.task05.dto;

import lombok.*;

import java.util.Map;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Getter
@Setter
public class Event {
    private String id;
    private int principalId;
    private String createdAt;
    private Map<String, String> body;
}