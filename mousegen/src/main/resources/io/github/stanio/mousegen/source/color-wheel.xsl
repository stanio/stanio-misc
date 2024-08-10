<?xml version="1.0" encoding="UTF-8"?>
<!-- 
  - SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
  - SPDX-License-Identifier: 0BSD
  -->

<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:ColorWheel="java://class/io.github.stanio.mousegen.source.AnimationTransform"
    xmlns:svg="http://www.w3.org/2000/svg"
    extension-element-prefixes="ColorWheel"
    exclude-result-prefixes="svg">
  <!-- https://loading.io/spinner/wedges/-pie-wedge-pizza-circle-round-rotate -->
  <!-- https://github.com/ful1e5/Bibata_Cursor/blob/v2.0.4/svg/original/wait.svg -->

  <xsl:param name="colorWheel" />

  <xsl:template match="svg:*[svg:animateTransform[@type='rotate']]">
    <xsl:variable name="colorWheel" select="ColorWheel:cast($colorWheel)" />
    <xsl:copy>
      <xsl:copy-of select="@*[name() != 'transform']" />
      <xsl:attribute name="transform">
        <xsl:value-of select="ColorWheel:rotate($colorWheel, svg:animateTransform/@*)" />
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
