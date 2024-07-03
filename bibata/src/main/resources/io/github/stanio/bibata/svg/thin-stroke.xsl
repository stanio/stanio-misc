<?xml version="1.0" encoding="UTF-8"?>
<!--
  - SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
  - SPDX-License-Identifier: 0BSD
  -->

<!--
  fixed-width-stroke
  expand-fill-stroke
  stroke-only

  Rename to:

  fixed-stroke
  fill-stroke
  fixed-fill
  -->
<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:svg="http://www.w3.org/2000/svg"
    xmlns="http://www.w3.org/2000/svg"
    xmlns:Math="java://class/java.lang.Math"
    extension-element-prefixes="Math"
    exclude-result-prefixes="svg">

  <!-- Used as selector criteria, only. -->
  <xsl:param name="base-width" select="16" />
  <xsl:variable name="width-tolerance" select="$base-width div 16" />
  <xsl:variable name="stroke-under-min-width" select="$base-width div 8 * 15" />

  <xsl:param name="stroke-diff" select="0" />
  <xsl:param name="fill-diff" select="0" />
  <xsl:variable name="stroke-only-diff" select="$stroke-diff - $fill-diff" />

  <xsl:template match="/">
    <xsl:choose>
      <xsl:when test="$fill-diff &lt; 0">
        <xsl:message terminate="yes">
          Illegal parameter: $fill-diff (<xsl:value-of select="$fill-diff" />) &lt; 0
        </xsl:message>
      </xsl:when>
      <xsl:when test="$fill-diff &gt; 0">
        <xsl:apply-templates mode="expand-fill" />
      </xsl:when>
      <xsl:otherwise>
        <xsl:apply-templates />
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <!--
    - Default mode: Stroke-only (shrink or expand)
    -->

  <xsl:template name="adjust-stroke-over"
      match="@stroke-width[ number() = $base-width
          or Math:abs(number() - $base-width) &lt; $width-tolerance ]">
    <xsl:param name="stroke-width" select="number()" />

    <xsl:attribute name="stroke-width">
      <xsl:value-of select="$stroke-width + $stroke-only-diff" />
    </xsl:attribute>
    <xsl:attribute name="_stroke-width">
      <xsl:value-of select="$stroke-width" />
    </xsl:attribute>
  </xsl:template>

  <xsl:template name="adjust-stroke-under"
      match="@stroke-width[ number() &gt;= $stroke-under-min-width ]">
    <xsl:param name="stroke-width" select="number()" />

    <xsl:attribute name="stroke-width">
      <xsl:value-of select="$stroke-width + 2 * $stroke-diff" />
    </xsl:attribute>
    <xsl:attribute name="_stroke-width">
      <xsl:value-of select="$stroke-width" />
    </xsl:attribute>
  </xsl:template>

  <xsl:template priority="1"
      match="@stroke-width[ contains(../@class, 'fixed-width-stroke')
                            or contains(../@class, 'expand-fill-stroke') ]">
    <xsl:copy-of select="." />
  </xsl:template>

  <!--
    - Expand fill ($fill-diff > 0)
    -->

  <xsl:template mode="expand-fill"
      match="@stroke-width[ number() = $base-width
                 or Math:abs(number() - $base-width) &lt; $width-tolerance ]">
    <xsl:call-template name="adjust-stroke-over" />
  </xsl:template>

  <xsl:template mode="expand-fill"
      match="svg:*[ @stroke-width &gt;= $stroke-under-min-width
                    and @fill and not(@fill = 'none')
                    and not(contains(@class, 'fixed-width-stroke')
                            or contains(@class, 'expand-fill-stroke')
                            or contains(@class, 'stroke-only')) ]">
    <g>
      <xsl:copy-of select="@id" />
      <xsl:copy-of select="@filter" />
      <xsl:copy-of select="@mask" />
      <xsl:copy-of select="@clip-path" />
      <xsl:copy>
        <xsl:copy-of select="@*[not(name() = 'id'
                                    or name() = 'filter'
                                    or name() = 'mask'
                                    or name() = 'clip-path')]" />
        <xsl:attribute name="fill">none</xsl:attribute>
        <xsl:call-template name="adjust-stroke-under">
          <xsl:with-param name="stroke-width" select="@stroke-width" />
        </xsl:call-template>
        <xsl:copy-of select="node()" />
      </xsl:copy>
      <xsl:copy>
        <xsl:copy-of select="@*[not(name() = 'id'
                                    or name() = 'filter'
                                    or name() = 'mask'
                                    or name() = 'clip-path')]" />
        <xsl:attribute name="stroke">
          <xsl:value-of select="@fill" />
        </xsl:attribute>
        <xsl:attribute name="stroke-width">
          <xsl:value-of select="2 * $fill-diff" />
        </xsl:attribute>
      </xsl:copy>
    </g>
  </xsl:template>

  <xsl:template mode="expand-fill"
      match="@stroke-width[ number() &gt;= $stroke-under-min-width ]">
    <xsl:call-template name="adjust-stroke-under" />
  </xsl:template>

  <xsl:template mode="expand-fill"
      match="@stroke-width[ contains(../@class, 'expand-fill-stroke') ]">
    <xsl:param name="stroke-width" select="number()" />

    <xsl:attribute name="stroke-width">
      <xsl:value-of select="$stroke-width + 2 * $fill-diff" />
    </xsl:attribute>
    <xsl:attribute name="_stroke-width">
      <xsl:value-of select="$stroke-width" />
    </xsl:attribute>
  </xsl:template>

  <xsl:template mode="expand-fill"
      match="@stroke-width[ contains(../@class, 'stroke-only') ]">
    <xsl:param name="stroke-width" select="number()" />

    <xsl:attribute name="stroke-width">
      <xsl:value-of select="$stroke-width + 2 * $stroke-only-diff" />
    </xsl:attribute>
    <xsl:attribute name="_stroke-width">
      <xsl:value-of select="$stroke-width" />
    </xsl:attribute>
  </xsl:template>

  <xsl:template mode="expand-fill" priority="1"
      match="@stroke-width[ contains(../@class, 'fixed-width-stroke') ]">
    <xsl:copy-of select="." />
  </xsl:template>

  <!-- Identity copy -->
  <xsl:template match="@*|node()">
    <xsl:copy>
      <xsl:apply-templates select="@*|node()"/>
    </xsl:copy>
  </xsl:template>

  <xsl:template mode="expand-fill" match="@*|node()">
    <xsl:copy>
      <xsl:apply-templates select="@*|node()" mode="expand-fill" />
    </xsl:copy>
  </xsl:template>

</xsl:stylesheet>
