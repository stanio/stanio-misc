<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:svg="http://www.w3.org/2000/svg"
    exclude-result-prefixes="xsl svg"
    xmlns="http://www.w3.org/2000/svg">

  <xsl:param name="shadow-blur" select="3" />
  <xsl:param name="shadow-dx" select="12" />
  <xsl:param name="shadow-dy" select="6" />
  <xsl:param name="shadow-opacity" select="0.5" />

  <xsl:template match="/svg:svg">
    <xsl:copy>
      <xsl:copy-of select="@*" />
      <xsl:attribute name="filter">url(#drop-shadow)</xsl:attribute>
      <xsl:apply-templates />
      <xsl:if test="not(.//*[@id='drop-shadow'])">
        <!-- Insert new -->
        <defs>
          <filter id="drop-shadow" filterUnits="userSpaceOnUse">
            <xsl:call-template name="drop-shadow" />
          </filter>
        </defs>
        <xsl:text>&#xA;</xsl:text>
      </xsl:if>
    </xsl:copy>
  </xsl:template>

  <!-- Update existing -->
  <xsl:template match="svg:filter[@id='drop-shadow']">
    <xsl:copy>
      <xsl:attribute name="id">drop-shadow</xsl:attribute>
      <xsl:attribute name="filterUnits">userSpaceOnUse</xsl:attribute>
      <xsl:call-template name="drop-shadow" />
    </xsl:copy>
  </xsl:template>

  <xsl:template name="drop-shadow">
    <xsl:comment> https://www.w3.org/TR/filter-effects-1/#feDropShadowElement </xsl:comment>
    <!-- https://drafts.fxtf.org/filter-effects/#feDropShadowElement -->
    <feGaussianBlur in="SourceAlpha" stdDeviation="{$shadow-blur}" />
    <feOffset dx="{$shadow-dx}" dy="{$shadow-dy}" result="offsetblur" />
    <feFlood flood-color="black" flood-opacity="{$shadow-opacity}" />
    <feComposite in2="offsetblur" operator="in" />
    <feMerge>
      <feMergeNode />
      <feMergeNode in="SourceGraphic" />
    </feMerge>
  </xsl:template>

  <!-- Identity copy -->
  <xsl:template match="@*|node()">
    <xsl:copy>
      <xsl:apply-templates select="@*|node()"/>
    </xsl:copy>
  </xsl:template>

</xsl:stylesheet>
