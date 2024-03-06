<?xml version="1.0" encoding="UTF-8"?>
<!-- 
  - SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
  - SPDX-License-Identifier: 0BSD
  -->

<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:svg="http://www.w3.org/2000/svg"
    xmlns:xlink="http://www.w3.org/1999/xlink"
    exclude-result-prefixes="xsl svg"
    xmlns="http://www.w3.org/2000/svg">

  <xsl:template match="/svg:svg">
    <svg xmlns:xlink="http://www.w3.org/1999/xlink">
      <xsl:apply-templates select="@*|node()" />
    </svg>
  </xsl:template>

  <xsl:template match="@href[../*[namespace-uri()='http://www.w3.org/2000/svg']]">
    <xsl:attribute name="href" namespace="http://www.w3.org/1999/xlink">
      <xsl:value-of select="." />
    </xsl:attribute>
  </xsl:template>

  <!-- REVISIT: lower-case(@paint-order) -->
  <xsl:template match="*[contains(normalize-space(@paint-order), 'stroke fill')]">
    <xsl:variable name="id" select="generate-id()" />
    <use xlink:href="#{$id}">
      <xsl:copy-of select="@*[starts-with(name(), 'stroke')]" />
    </use>
    <xsl:text>&#xA;</xsl:text>
    <xsl:copy>
      <xsl:attribute name="id">
        <xsl:value-of select="$id" />
      </xsl:attribute>
      <xsl:apply-templates select="node()|@*[not(starts-with(name(), 'stroke') or name()='paint-order')]" />
    </xsl:copy>
  </xsl:template>

  <xsl:template match="svg:clipPath">
    <xsl:copy>
      <xsl:copy-of select="@id" />
      <!-- https://issues.apache.org/jira/browse/BATIK-1323 -->
      <xsl:attribute name="shape-rendering">geometricPrecision</xsl:attribute>
      <xsl:apply-templates select="@*|node()" />
    </xsl:copy>
  </xsl:template>

  <!-- REVISIT: <feDropShadow> -->

  <!-- Identity copy -->
  <xsl:template match="@*|node()">
    <xsl:copy>
      <xsl:apply-templates select="@*|node()"/>
    </xsl:copy>
  </xsl:template>

</xsl:stylesheet>
