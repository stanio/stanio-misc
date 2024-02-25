<?xml version="1.0" encoding="UTF-8"?>
<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:svg="http://www.w3.org/2000/svg"
    exclude-result-prefixes="xsl svg"
    xmlns="http://www.w3.org/2000/svg">

  <xsl:template match="/svg:svg">
    <xsl:copy>
      <xsl:copy-of select="@*" />
      <xsl:text>&#xA;</xsl:text>
      <!-- https://github.com/weisJ/jsvg/issues/61 -->
      <!-- XXX: https://github.com/weisJ/jsvg/issues/62 -->
      <g filter="url(#drop-shadow)">
        <xsl:text>&#xA;</xsl:text>
        <xsl:copy-of select="node()" />
      </g>
      <xsl:text>&#xA;</xsl:text>
      <defs>
        <!-- https://www.w3.org/TR/filter-effects-1/#feDropShadowElement -->
        <!-- https://drafts.fxtf.org/filter-effects/#feDropShadowElement -->
        <filter id="drop-shadow" filterUnits="userSpaceOnUse">
          <feGaussianBlur in="SourceAlpha" stdDeviation="3" />
          <feOffset dx="12" dy="6" result="offsetblur" />
          <feFlood flood-color="black" flood-opacity="0.5" />
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

</xsl:stylesheet>
