<?xml version="1.0" encoding="UTF-8"?>
<!-- 
  - SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
  - SPDX-License-Identifier: 0BSD
  -->

<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:svg="http://www.w3.org/2000/svg"
    xmlns:xlink="http://www.w3.org/1999/xlink"
    exclude-result-prefixes="svg"
    xmlns="http://www.w3.org/2000/svg">

  <xsl:template match="/svg:svg">
    <svg xmlns:xlink="http://www.w3.org/1999/xlink">
      <xsl:apply-templates select="@*|node()" />
    </svg>
  </xsl:template>

  <xsl:template match="@href[parent::*[namespace-uri()='http://www.w3.org/2000/svg']
                             and not(../@xlink:href)]">
    <xsl:attribute name="href" namespace="http://www.w3.org/1999/xlink">
      <xsl:value-of select="." />
    </xsl:attribute>
  </xsl:template>

  <!-- REVISIT: lower-case(@paint-order) -->
  <xsl:template match="svg:*[ normalize-space(@paint-order) = 'stroke'
              or contains(substring-after(@paint-order, 'stroke'), 'fill') ]">
    <!-- <xsl:variable name="id" select="generate-id()" /> -->
    <g>
      <xsl:copy-of select="@id" />
      <xsl:apply-templates select="@*[ name() = 'filter'
                                       or name() = 'mask'
                                       or name() = 'clip-path' ]" />
      <xsl:text>&#xA;</xsl:text>
      <xsl:copy>
        <xsl:apply-templates select="@*[not(name() = 'id'
                                            or name() = 'filter'
                                            or name() = 'mask'
                                            or name() = 'clip-path')]" />
        <xsl:attribute name="fill">
          <xsl:value-of select="@stroke" />
        </xsl:attribute>
        <xsl:attribute name="class">
          <xsl:value-of select="@class" />
          <xsl:text> outer-stroke</xsl:text>
        </xsl:attribute>
        <xsl:apply-templates />
      </xsl:copy>
      <xsl:text>&#xA;</xsl:text>
      <xsl:copy>
        <!-- <xsl:attribute name="id">
          <xsl:value-of select="$id" />
        </xsl:attribute> -->
        <xsl:apply-templates select="@*[not(name() = 'id'
                                            or name() = 'filter'
                                            or name() = 'mask'
                                            or name() = 'clip-path')]" />
        <xsl:attribute name="stroke">
          <xsl:value-of select="@fill" />
        </xsl:attribute>
        <xsl:attribute name="stroke-width">0</xsl:attribute>
        <xsl:attribute name="class">
          <xsl:value-of select="@class" />
          <xsl:text> expand-fill-stroke</xsl:text>
        </xsl:attribute>
        <xsl:apply-templates />
      </xsl:copy>
      <xsl:text>&#xA;</xsl:text>
    </g>
  </xsl:template>

  <xsl:template match="svg:clipPath">
    <xsl:copy>
      <xsl:copy-of select="@id" />
      <!-- https://issues.apache.org/jira/browse/BATIK-1323 -->
      <xsl:attribute name="shape-rendering">geometricPrecision</xsl:attribute>
      <xsl:apply-templates select="@*|node()" />
    </xsl:copy>
  </xsl:template>

  <xsl:template match="svg:feDropShadow">
    <!-- https://www.w3.org/TR/filter-effects-1/#feDropShadowElement -->
    <feGaussianBlur in="{@in}" stdDeviation="{@stdDeviation}"/>
    <xsl:text>&#xA;</xsl:text>
    <feOffset dx="{@dx}" dy="{@dy}" result="offsetBlur"/>
    <xsl:text>&#xA;</xsl:text>
    <feFlood flood-color="{@flood-color}" flood-opacity="{@flood-opacity}"/>
    <xsl:text>&#xA;</xsl:text>
    <feComposite in2="offsetBlur" operator="in"/>
    <xsl:text>&#xA;</xsl:text>
    <feMerge>
      <feMergeNode/>
      <!-- Simulate ternary operator: 0 div 0 = NaN and produces empty substring()
           result: https://www.w3.org/TR/xpath-10/#function-substring (unusual cases) -->
      <feMergeNode in="{concat( substring(@in, boolean(normalize-space(@in)) div 0),
                                substring('SourceGraphic', not(normalize-space(@in)) div 0) )}"/>
    </feMerge>
  </xsl:template>

  <!-- Identity copy -->
  <xsl:template match="@*|node()">
    <xsl:copy>
      <xsl:apply-templates select="@*|node()"/>
    </xsl:copy>
  </xsl:template>

</xsl:stylesheet>
