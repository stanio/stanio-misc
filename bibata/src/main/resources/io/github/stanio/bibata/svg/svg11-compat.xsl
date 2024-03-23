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

  <xsl:template match="@href[parent::*[namespace-uri()='http://www.w3.org/2000/svg']
                             and not(../@xlink:href)]">
    <xsl:copy />
    <xsl:attribute name="href" namespace="http://www.w3.org/1999/xlink">
      <xsl:value-of select="." />
    </xsl:attribute>
  </xsl:template>

  <!-- REVISIT: lower-case(@paint-order) -->
  <xsl:template match="svg:*[contains(normalize-space(@paint-order), 'stroke fill')]">
    <xsl:choose>
      <xsl:when test="@id">
        <xsl:call-template name="stroke-under-fill">
          <xsl:with-param name="id" select="@id" />
        </xsl:call-template>
      </xsl:when>
      <xsl:otherwise>
        <xsl:call-template name="stroke-under-fill">
          <xsl:with-param name="id" select="generate-id()" />
        </xsl:call-template>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="stroke-under-fill">
    <xsl:param name="id" />
    <use href="#{$id}" xlink:href="#{$id}">
      <xsl:copy-of select="@*[starts-with(name(), 'stroke')]" />
      <xsl:attribute name="originalPaintOrder">
        <xsl:value-of select="@paint-order" />
      </xsl:attribute>
    </use>
    <xsl:text>&#xA;</xsl:text>
    <xsl:copy>
      <xsl:attribute name="id">
        <xsl:value-of select="$id" />
      </xsl:attribute>
      <xsl:apply-templates select="node()|@*[not(starts-with(name(), 'stroke')
                                             or name() = 'paint-order')]" />
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
