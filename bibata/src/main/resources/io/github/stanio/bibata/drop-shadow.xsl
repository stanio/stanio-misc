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

  <!-- Avoid duplicating previous update -->
  <xsl:template match="/svg:svg[not(.//*[@id='drop-shadow'])]">
    <xsl:copy>
      <xsl:copy-of select="@*" />
      <xsl:attribute name="filter">url(#drop-shadow)</xsl:attribute>
      <xsl:copy-of select="node()" />
      <defs>
        <filter id="drop-shadow" filterUnits="userSpaceOnUse">
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
        </filter>
      </defs>
      <xsl:text>&#xA;</xsl:text>
    </xsl:copy>
  </xsl:template>

  <!-- Fall back to identity copy -->
  <xsl:template match="@*|node()">
    <xsl:copy>
      <xsl:apply-templates select="@*|node()"/>
    </xsl:copy>
  </xsl:template>

</xsl:stylesheet>
