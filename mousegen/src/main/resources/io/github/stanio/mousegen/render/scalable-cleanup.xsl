<?xml version="1.0" encoding="UTF-8"?>
<!-- 
  - SPDX-FileCopyrightText: 2025 Stanio <stanio AT yahoo DOT com>
  - SPDX-License-Identifier: 0BSD
  -->

<xsl:stylesheet version="1.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:svg="http://www.w3.org/2000/svg"
    xmlns="http://www.w3.org/2000/svg"
    exclude-result-prefixes="svg">

  <xsl:template match="*[@id='cursor-hotspot' or
                         @id='align-anchor' or
                         contains(@class, 'align-anchor')]">
    <!-- Remove elements -->
  </xsl:template>

  <xsl:template match="@align-anchor|
                       @class[contains(., 'fixed-stroke')
                              or contains(., 'fill-stroke')
                              or contains(., 'fixed-fill')]">
    <!-- Remove attributes -->
  </xsl:template>

  <!-- Identity copy (removing comments and PIs) -->
  <xsl:template match="@*|*">
    <xsl:copy>
      <xsl:apply-templates select="@*|*"/>
    </xsl:copy>
  </xsl:template>

</xsl:stylesheet>
