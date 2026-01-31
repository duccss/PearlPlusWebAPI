package dev.zenith.ppapi.api.model;

import java.util.List;

public record PearlStatusResponse(
    List<String> pearls,
    List<String> output
) { }
