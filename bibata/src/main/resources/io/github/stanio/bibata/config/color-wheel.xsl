<?xml version="1.0" encoding="UTF-8"?>
<!-- 
  - SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
  - SPDX-License-Identifier: 0BSD
  -->

<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:ColorWheel="java://class/io.github.stanio.bibata.config.ColorWheel"
    xmlns:svg="http://www.w3.org/2000/svg"
    exclude-result-prefixes="xsl bibata svg">

  <xsl:param name="colorWheel" />
  <xsl:param name="snapshotTime" select="0" />

  <xsl:template match="svg:*[svg:animateTransform]">
    <xsl:variable name="colorWheel" select="ColorWheel:cast($colorWheel)" />
    <xsl:copy>
      <xsl:copy-of select="@*[name() != 'transform']" />
      <xsl:attribute name="transform">
        <xsl:value-of select="ColorWheel:interpolateTransform(
            $colorWheel, svg:animateTransform/@*, $snapshotTime)" />
      </xsl:attribute>
      <xsl:apply-templates />
    </xsl:copy>
  </xsl:template>

  <xsl:template match="svg:animateTransform">
    <xsl:processing-instruction name="{name()}">
      <xsl:for-each select="@*">
        <xsl:value-of select="name()" />
        <xsl:text>="</xsl:text>
        <xsl:value-of select="." />
        <xsl:text>" </xsl:text>
      </xsl:for-each>
    </xsl:processing-instruction>
  </xsl:template>

  <!-- Identity copy -->
  <xsl:template match="@*|node()">
    <xsl:copy>
      <xsl:apply-templates select="@*|node()"/>
    </xsl:copy>
  </xsl:template>

</xsl:stylesheet>
