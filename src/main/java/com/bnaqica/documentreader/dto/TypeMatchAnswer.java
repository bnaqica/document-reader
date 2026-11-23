package com.bnaqica.documentreader.dto;

/** Whether a proposed type name means an existing type; {@code existingName} is that type's name, or null. */
public record TypeMatchAnswer(boolean matchesExisting, String existingName) {}
