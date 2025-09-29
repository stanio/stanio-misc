<?xml version="1.0" encoding="UTF-8"?>
<!-- 
  - SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
  - SPDX-License-Identifier: 0BSD
  -->

<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:svg="http://www.w3.org/2000/svg"
    xmlns:xlink="http://www.w3.org/1999/xlink"
    xmlns="http://www.w3.org/2000/svg"
    exclude-result-prefixes="svg">

  <xsl:template match="/svg:svg">
    <xsl:variable name="defs" select=".//svg:defs"/>
    <xsl:variable name="posf" select=".//svg:*[ normalize-space(@paint-order) = 'stroke'
              or contains(substring-after(@paint-order, 'stroke'), 'fill') ]"/>
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
          <xsl:call-template name="posf">
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
  <xsl:template name="posf">
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
    <!-- <xsl:variable name="id" select="generate-id()" /> -->
    <g>
      <xsl:copy-of select="@id" />
      <xsl:apply-templates select="@*[ name() = 'filter'
                                       or name() = 'mask'
                                       or name() = 'clip-path' ]" />
      <use xlink:href="#{generate-id()}">
        <xsl:attribute name="fill">none</xsl:attribute>
        <xsl:apply-templates select="@stroke" />
        <xsl:apply-templates select="@stroke-opacity" />
        <xsl:apply-templates select="@stroke-width" />
      </use>
      <use xlink:href="#{generate-id()}">
        <xsl:apply-templates select="@fill" />
        <xsl:apply-templates select="@fill-opacity" />
      </use>
    </g>
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
