<?xml version="1.0" encoding="UTF-8"?>
<!--
  - SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
  - SPDX-License-Identifier: 0BSD
  -->

<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:svg="http://www.w3.org/2000/svg"
    xmlns="http://www.w3.org/2000/svg">

  <xsl:param name="new-width" select="12" />
  <xsl:param name="base-width" select="16" />

  <xsl:variable name="base-max-width" select="$base-width + 2" />

  <!--
    - The visible stroke width of shapes with paint-order="stroke fill" is
    - half the specified stroke-width.
    -->
  <xsl:variable name="stroke-under-diff"
      select="($base-width - $new-width) * 2" />

  <xsl:variable name="stroke-under-min-width" select="$base-width * 2 - 4" />

  <!--
    - XXX: Note, if the stroke-width doesn't match the expected $base-width,
    - f.e. "16.8" vs. "16", resulting offsets will vary.
    -->
  <xsl:template match="@stroke-width[not(../@originalStrokeWidth)
                                     and not(contains(../@class, 'fixed-width-stroke'))
                                     and number() &gt;= $base-width
                                     and number() &lt; $base-max-width]">
    <xsl:attribute name="stroke-width">
      <xsl:value-of select="$new-width" />
    </xsl:attribute>
    <xsl:attribute name="originalStrokeWidth">
      <xsl:value-of select="." />
    </xsl:attribute>
  </xsl:template>

  <xsl:template match="@stroke-width[not(../@originalStrokeWidth)
                                     and not(contains(../@class, 'fixed-width-stroke'))
                                     and number() &gt;= $stroke-under-min-width]">
    <xsl:attribute name="stroke-width">
      <xsl:value-of select="number() - $stroke-under-diff" />
    </xsl:attribute>
    <xsl:attribute name="originalStrokeWidth">
      <xsl:value-of select="." />
    </xsl:attribute>
  </xsl:template>

  <!-- Identity copy -->
  <xsl:template match="@*|node()">
    <xsl:copy>
      <xsl:apply-templates select="@*|node()"/>
    </xsl:copy>
  </xsl:template>

</xsl:stylesheet>
