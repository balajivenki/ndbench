package com.netflix.ndbench.plugin.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Mapper class to generate the token util
 * Created by bvenkatesan on 9/1/16.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@AllArgsConstructor
public class TokenMapper {
    private String token;
    private String hostname;
    private String port;
    private String rack;
    private String zone;
    private String dc;
}

