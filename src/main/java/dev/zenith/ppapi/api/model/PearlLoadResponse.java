package dev.zenith.ppapi.api.model;

import java.util.List;

public record PearlLoadResponse(
    String status,
    List<String> output
) { }
