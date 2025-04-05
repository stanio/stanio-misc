<?xml version="1.0" encoding="UTF-8"?>
<!-- 
  - SPDX-FileCopyrightText: 2024 Stanio <stanio AT yahoo DOT com>
  - SPDX-License-Identifier: 0BSD
  -->

<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:Dump="java://class.internal/io.github.stanio.mousegen.dump.providers.MousecapeDumpProvider$DumpHandler"
    extension-element-prefixes="Dump">

  <xsl:output method="text" />

  <xsl:param name="dumpHandler" />
  <xsl:variable name="handler" select="Dump:cast($dumpHandler)" />

  <xsl:template match="/plist">
    <xsl:apply-templates select="*" />
    <xsl:text>&#xA;</xsl:text>
  </xsl:template>

  <xsl:template match="key">
    <xsl:param name="indent" />
    <xsl:param name="break" select="'&#xA;'" />
    <xsl:value-of select="$break" />
    <xsl:value-of select="$indent" />
    <xsl:value-of select="normalize-space()" />
    <xsl:text>: </xsl:text>
  </xsl:template>

  <xsl:template match="date | integer | string">
    <xsl:value-of select="normalize-space()" />
  </xsl:template>

  <xsl:template match="real">
    <xsl:value-of select="round(number() * 1000) div 1000" />
  </xsl:template>

  <xsl:template match="true | false">
    <xsl:value-of select="name()" />
  </xsl:template>

  <xsl:template match="dict">
    <xsl:param name="indent" />
    <xsl:apply-templates select="*">
      <xsl:with-param name="indent" select="concat($indent, '  ')" />
    </xsl:apply-templates>
  </xsl:template>

  <xsl:template match="array">
    <xsl:param name="indent" />
    <xsl:param name="break" select="'&#xA;'" />
    <xsl:for-each select="*">
      <xsl:value-of select="$break" />
      <xsl:value-of select="$indent" />
      <xsl:text>  - </xsl:text>
      <xsl:apply-templates select=".">
        <xsl:with-param name="indent" select="concat($indent, '  ')" />
      </xsl:apply-templates>
    </xsl:for-each>
  </xsl:template>

  <xsl:template match="data">
    <xsl:text>base64-length(</xsl:text>
    <xsl:value-of select="string-length(normalize-space())" />
    <xsl:text>)</xsl:text>
  </xsl:template>

  <xsl:template match="/plist/dict/dict[preceding-sibling::key[.='Cursors']]/key">
    <xsl:param name="indent" />
    <xsl:param name="break" select="'&#xA;'" />
    <xsl:value-of select="$break" />
    <xsl:value-of select="$indent" />
    <xsl:value-of select="Dump:cursorName($handler, .)" />
    <xsl:text>: </xsl:text>
  </xsl:template>

  <xsl:template match="/plist/dict/dict[preceding-sibling::key[.='Cursors']]/dict">
    <xsl:param name="indent" />

    <xsl:variable name="width" select="key[.='PointsWide']/following-sibling::real" />
    <xsl:variable name="height" select="key[.='PointsHigh']/following-sibling::real" />
    <xsl:variable name="xHot" select="key[.='HotSpotX']/following-sibling::real" />
    <xsl:variable name="yHot" select="key[.='HotSpotY']/following-sibling::real" />
    <xsl:variable name="frameCount" select="key[.='FrameCount']/following-sibling::integer" />
    <xsl:variable name="frameDuration" select="key[.='FrameDuration']/following-sibling::real" />
    <xsl:value-of select="Dump:cursorProperties($handler,
        number($width), number($height), number($xHot), number($yHot), number($frameCount), number($frameDuration))" />

    <xsl:apply-templates select="*">
      <xsl:with-param name="indent" select="concat($indent, '  ')" />
    </xsl:apply-templates>

    <xsl:value-of select="Dump:completeCursor($handler)" />
  </xsl:template>

  <xsl:template match="/plist/dict/dict[preceding-sibling::key[.='Cursors']]/dict/array[preceding-sibling::key[.='Representations']]/data">
    <xsl:value-of select="Dump:saveRepresentation($handler, .)" />
  </xsl:template>

</xsl:stylesheet>
