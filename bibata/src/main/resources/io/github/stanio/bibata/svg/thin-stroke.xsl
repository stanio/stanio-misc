<?xml version="1.0" encoding="UTF-8"?>
<!--
  - SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
  - SPDX-License-Identifier: 0BSD
  -->

<!--
  <path stroke-width="$base-width" or class="variable-single-stroke" />
  <path stroke-width="> 2 * $base-width" or class="variable-double-stroke" />

  <path class="stroke-fill" />
  <path class="outer-stroke" />

  shrink-only-stroke
  variable-stroke-only


  fixed-width-stroke

  expand-fill-stroke (expand-fill)
  outside-stroke (stroke-only)

  shrink-only-stroke (expand-fill)

  stroke-only (stroke-only, expand-fill)
  stroke-over     single-width / auto
  stroke-outside  double-width / auto
  -->
<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:svg="http://www.w3.org/2000/svg"
    xmlns="http://www.w3.org/2000/svg"
    xmlns:Math="java://class/java.lang.Math"
    extension-element-prefixes="Math"
    exclude-result-prefixes="svg">

  <xsl:param name="base-width" select="16" />
  <xsl:param name="new-width" select="12" />
  <xsl:param name="expand-fill" select="false()" />

  <xsl:variable name="width-diff" select="$new-width - $base-width" />
  <xsl:variable name="width-tolerance" select="$base-width div 16" />

  <xsl:variable name="stroke-under-diff" select="2 * $width-diff" />
  <xsl:variable name="stroke-under-min-width" select="$base-width div 8 * 15" />

  <xsl:variable name="expand-mode"
      select="$expand-fill and ($new-width &lt; $base-width)"/>

  <xsl:template match="/">
    <xsl:choose>
      <xsl:when test="$expand-mode">
        <xsl:apply-templates mode="expand-fill" />
      </xsl:when>
      <xsl:otherwise>
        <xsl:apply-templates />
      </xsl:otherwise>
    </xsl:choose>
  </xsl:template>

  <xsl:template name="adjust-stroke-over">
    <xsl:param name="stroke-width" select="number()" />

    <xsl:attribute name="stroke-width">
      <!-- Not just $new-width as the $stroke-width
           may not exactly equal the $base-width -->
      <xsl:value-of select="$stroke-width + $width-diff" />
    </xsl:attribute>
    <xsl:attribute name="_stroke-width">
      <xsl:value-of select="$stroke-width" />
    </xsl:attribute>
  </xsl:template>

  <xsl:template name="adjust-stroke-outside">
    <xsl:param name="stroke-width" select="number()" />

    <xsl:attribute name="stroke-width">
      <xsl:value-of select="$stroke-width + $stroke-under-diff" />
    </xsl:attribute>
    <xsl:attribute name="_stroke-width">
      <xsl:value-of select="$stroke-width" />
    </xsl:attribute>
  </xsl:template>

  <!--
    - Shrink stroke (stroke-only)
    -->

  <xsl:template
      match="@stroke-width[ contains(../@class, 'stroke-over')
                            or number() = $base-width
                 or Math:abs(number() - $base-width) &lt; $width-tolerance ]">
    <xsl:call-template name="adjust-stroke-over" />
  </xsl:template>

  <xsl:template
      match="@stroke-width[ contains(../@class, 'outer-stroke')
                            or number() &gt;= $stroke-under-min-width ]">
    <xsl:call-template name="adjust-stroke-outside" />
  </xsl:template>

  <xsl:template
      match="@stroke-width[ contains(../@class, 'fixed-width-stroke')
                            or contains(../@class, 'expand-fill-stroke')
                            or contains(../@class, 'shrink-only-stroke') ]">
    <xsl:copy-of select="." />
  </xsl:template>

  <!--
    - Expand fill ($new-width < $base-width)
    -->

  <xsl:template mode="expand-fill"
      match="@stroke-width[ contains(../@class, 'stroke-over')
                            or number() = $base-width
                 or Math:abs(number() - $base-width) &lt; $width-tolerance ]">
    <xsl:call-template name="adjust-stroke-over" />
  </xsl:template>

  <xsl:template mode="expand-fill"
      match="@stroke-width[ contains(../@class, 'expand-fill-stroke') ]">
    <xsl:param name="stroke-width" select="number()" />

    <xsl:attribute name="stroke-width">
      <!-- assert ($stroke-under-diff < 0) -->
      <xsl:value-of select="$stroke-width - $stroke-under-diff" />
    </xsl:attribute>
    <xsl:attribute name="_stroke-width">
      <xsl:value-of select="$stroke-width" />
    </xsl:attribute>
  </xsl:template>

  <xsl:template mode="expand-fill"
      match="svg:*[ (@stroke-width &gt;= $stroke-under-min-width
                        or contains(@class, 'variable-stroke'))
                    and not(contains(@class, 'fixed-width-stroke')
                            or contains(@class, 'expand-fill-stroke')
                            or contains(@class, 'shrink-only-stroke')
                            or contains(@class, 'stroke-only')
                            or contains(@class, 'adjust-stroke-only')
                            or contains(@class, 'outer-stroke')) ]">
    <!-- XXX: Use <g> to move filter, mask or clip-path to -->
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
        <xsl:copy-of select="node()" />
      </xsl:copy>
      <xsl:copy>
        <xsl:copy-of select="@*[not(name() = 'id'
                                    or name() = 'paint-order'
                                    or name() = 'filter'
                                    or name() = 'mask'
                                    or name() = 'clip-path')]" />
        <xsl:attribute name="stroke">
          <xsl:value-of select="concat( substring(@fill, boolean(normalize-space(@fill)) div 0),
                                        substring(@stroke, not(normalize-space(@fill)) div 0) )" />
        </xsl:attribute>
        <xsl:attribute name="stroke-width">
          <xsl:value-of select="-$stroke-under-diff" />
        </xsl:attribute>
      </xsl:copy>
    </g>
  </xsl:template>

  <xsl:template mode="expand-fill"
      match="@stroke-width[ contains(../@class, 'stroke-only')
                            or contains(../@class, 'shrink-only-stroke') ]">
    <xsl:call-template name="adjust-stroke-outside" />
  </xsl:template>

  <xsl:template mode="expand-fill"
      match="@stroke-width[ contains(../@class, 'outer-stroke')
                            or contains(../@class, 'fixed-width-stroke') ]">
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
