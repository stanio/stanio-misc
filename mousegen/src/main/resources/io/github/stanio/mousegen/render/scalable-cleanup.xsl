<?xml version="1.0" encoding="UTF-8"?>
<!-- 
  - SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
  - SPDX-License-Identifier: 0BSD
  -->

<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:svg="http://www.w3.org/2000/svg"
    xmlns:xlink="http://www.w3.org/1999/xlink"
    xmlns:Integer="java://class/java.lang.Integer"
    xmlns="http://www.w3.org/2000/svg"
    extension-element-prefixes="Integer"
    exclude-result-prefixes="svg">

  <xsl:template match="/svg:svg">
    <xsl:variable name="defs" select=".//svg:defs"/>
    <xsl:variable name="posf" select=".//svg:*[ (normalize-space(@paint-order) = 'stroke'
              or contains(substring-after(@paint-order, 'stroke'), 'fill')) and
              local-name() != 'use' ]"/>
    <svg xmlns:xlink="http://www.w3.org/1999/xlink">
      <xsl:apply-templates select="@id"/>
      <xsl:apply-templates select="@version"/>
      <xsl:apply-templates select="@width"/>
      <xsl:apply-templates select="@height"/>
      <xsl:apply-templates select="@viewBox"/>
      <xsl:apply-templates select="@*"/>
      <xsl:if test="$defs or $posf">
        <defs>
          <xsl:for-each select="$defs">
            <xsl:apply-templates select="*" />
          </xsl:for-each>
          <xsl:call-template name="def-posf">
            <xsl:with-param name="posf" select="$posf" />
          </xsl:call-template>
        </defs>
      </xsl:if>
      <xsl:apply-templates select="@*|*"/>
    </svg>
  </xsl:template>

  <xsl:template match="svg:defs | *[@display='none' or
      @id='cursor-hotspot' or @id='align-anchor' or contains(@class, 'align-anchor')]">
    <!-- Remove elements -->
  </xsl:template>

  <xsl:template match="@align-anchor | @class[contains(., 'fixed-stroke') or
      contains(., 'fill-stroke') or contains(., 'fixed-fill')]">
    <!-- Remove attributes -->
  </xsl:template>

  <!-- paint-order="stroke fill" -->
  <xsl:template name="def-posf">
    <xsl:param name="posf"/>
    <xsl:for-each select="$posf">
      <xsl:copy>
        <xsl:attribute name="id">
          <xsl:value-of select="generate-id()"/>
        </xsl:attribute>
        <xsl:apply-templates select="@*[not(name() = 'id' or
            name() = 'fill' or name() = 'fill-opacity' or name() = 'paint-order' or
            name() = 'stroke' or name() = 'stroke-opacity' or name() = 'stroke-width' or
            name() = 'filter' or name() = 'mask' or name() = 'clip-path')]" />
        <xsl:apply-templates />
      </xsl:copy>
    </xsl:for-each>
  </xsl:template>

  <xsl:template match="svg:*[ normalize-space(@paint-order) = 'stroke'
              or contains(substring-after(@paint-order, 'stroke'), 'fill') ]">
    <xsl:choose>
      <xsl:when test="local-name() = 'use'">
        <xsl:call-template name="break-posf">
          <xsl:with-param name="id" select="concat(@href, @xlink:href)" />
        </xsl:call-template>
      </xsl:when>
      <xsl:otherwise>
        <xsl:call-template name="break-posf"/>
      </xsl:otherwise>
    </xsl:choose>
    
  </xsl:template>

  <xsl:template name="break-posf">
    <xsl:param name="id" select="concat('#', generate-id())" />
    <g>
      <xsl:copy-of select="@id" />
      <xsl:apply-templates select="@*[ name() = 'filter'
                                       or name() = 'mask'
                                       or name() = 'clip-path' ]" />
      <!-- @stroke-opacity != '0' -->
      <xsl:if test="@stroke[ not(starts-with(normalize-space(), '#')) or
                             substring(normalize-space(), 8) != '00' ]">
        <use xlink:href="{$id}">
          <xsl:attribute name="fill">none</xsl:attribute>
          <xsl:apply-templates select="@stroke" />
          <xsl:apply-templates select="@stroke-opacity" />
          <xsl:apply-templates select="@stroke-width" />
        </use>
      </xsl:if>
      <use xlink:href="{$id}">
        <xsl:apply-templates select="@fill" />
        <xsl:apply-templates select="@fill-opacity" />
      </use>
    </g>
  </xsl:template>

  <!-- Transform fill="#RRGGBBAA" into fill="#RRGGBB" fill-opacity="AA / 255" -->
  <xsl:template match="@fill[ starts-with(normalize-space(), '#') and
                              string-length(normalize-space()) = 9 ]">
    <xsl:call-template name="extract-opacity"/>
  </xsl:template>

  <xsl:template match="@stroke[ starts-with(normalize-space(), '#') and
                                string-length(normalize-space()) = 9 ]">
    <xsl:call-template name="extract-opacity"/>
  </xsl:template>

  <xsl:template name="extract-opacity">
    <xsl:param name="attr-opacity" select="concat(local-name(), '-opacity')"/>
    <xsl:variable name="alpha" select="substring(normalize-space(), 8)"/>
    <xsl:choose>
      <xsl:when test="$alpha = '00'">
        <xsl:attribute name="{name()}">none</xsl:attribute>
      </xsl:when>
      <xsl:otherwise>
        <xsl:attribute name="{name()}">
          <xsl:value-of select="substring(normalize-space(), 1, 7)"/>
        </xsl:attribute>
        <xsl:if test="$alpha != 'FF'">
          <xsl:attribute name="{$attr-opacity}">
            <xsl:value-of select="round(Integer:parseInt($alpha, 16) * 100 div 255) div 100"/>
          </xsl:attribute>
        </xsl:if>
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template match="@color[ starts-with(normalize-space(), '#') and
                               string-length(normalize-space()) = 9 ]">
    <!-- Drop attribute -->
  </xsl:template>

  <xsl:template match="svg:*[ starts-with(normalize-space(@fill), '#') and
                              substring(normalize-space(@fill), 8) = '00' or
                              starts-with(normalize-space(@color), '#') and
                              substring(normalize-space(@color), 8) = '00' ]">
    <!-- Drop element -->
  </xsl:template>

  <!-- REVISIT: Or just on <use> elements? -->
  <xsl:template match="@href">
    <xsl:attribute name="xlink:href">
      <xsl:value-of select="."/>
    </xsl:attribute>
  </xsl:template>

  <!-- Identity copy (removing comments and PIs) -->
  <xsl:template match="*">
    <xsl:copy>
      <!-- Suggest attribute order -->
      <xsl:apply-templates select="@id"/>
      <xsl:apply-templates select="@href"/>
      <xsl:apply-templates select="@xlink:href"/>
      <xsl:apply-templates select="@d"/>
      <xsl:apply-templates select="@dx"/>
      <xsl:apply-templates select="@dy"/>
      <xsl:apply-templates select="@cx"/>
      <xsl:apply-templates select="@cy"/>
      <xsl:apply-templates select="@r"/>
      <xsl:apply-templates select="@x"/>
      <xsl:apply-templates select="@y"/>
      <xsl:apply-templates select="@width"/>
      <xsl:apply-templates select="@height"/>
      <xsl:apply-templates select="@rx"/>
      <xsl:apply-templates select="@ry"/>
      <xsl:apply-templates select="@fill"/>
      <xsl:apply-templates select="@fill-opacity"/>
      <xsl:apply-templates select="@stroke"/>
      <xsl:apply-templates select="@stroke-opacity"/>
      <xsl:apply-templates select="@stroke-width"/>
      <xsl:apply-templates select="@stroke-linejoin"/>
      <xsl:apply-templates select="@stroke-linecap"/>
      <xsl:apply-templates select="@paint-order"/>
      <xsl:apply-templates select="@*|*"/>
    </xsl:copy>
  </xsl:template>

  <xsl:template match="@*">
    <xsl:copy />
  </xsl:template>

</xsl:stylesheet>
