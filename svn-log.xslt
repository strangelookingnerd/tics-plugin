<?xml version="1.0"?>
<xsl:stylesheet 
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    version="1.0">
  <xsl:output 
    method="html" 
    encoding="UTF-8"
    indent="no"/>

  <xsl:template match="/">
    <html>
      <head>
        <title>Release Notes</title>
        <link rel="SHORTCUT ICON" href="/images/tiobe.ico" type="image/x-icon" />
        <link rel="stylesheet" href="/tics/tics.css" type="text/css" />
      </head>
      <body>
        <h2>Release Notes</h2>
        <xsl:apply-templates select="/log"/>
      </body>
    </html>
  </xsl:template>

  <xsl:template match="log">
    <table >
      <tr valign="top">
        <td><b>Release</b></td>
        <td><xsl:text disable-output-escaping="yes">&amp;nbsp;</xsl:text></td>
        <td><b>Date</b></td>
        <td><xsl:text disable-output-escaping="yes">&amp;nbsp;</xsl:text></td>
        <td><b>Note</b></td>
      </tr>
    <xsl:apply-templates select="logentry"/>
    </table>
  </xsl:template>

  <xsl:template match="logentry">
    <xsl:if test="string(number(substring(msg, 1, 1)))!='NaN'">
      <xsl:if test="substring-before(substring-after(msg, '] '), ': ')!='Rework'">
        <tr valign="top">
          <td>
            <xsl:value-of select="@revision"/>
          </td>
          <td><xsl:text disable-output-escaping="yes">&amp;nbsp;</xsl:text></td>
          <td nowrap="nowrap">
            <xsl:value-of select="substring-before(date, 'T')"/>
          </td>
          <td><xsl:text disable-output-escaping="yes">&amp;nbsp;</xsl:text></td>
          <td>
            <xsl:if test="contains(msg, '&#10;&#10;')">
              <xsl:value-of select="substring-before(msg, '&#10;&#10;')"/>
            </xsl:if>
            <xsl:if test="not(contains(msg, '&#10;&#10;'))">
              <xsl:value-of select="msg"/>
            </xsl:if>
          </td>
        </tr>
      </xsl:if>
    </xsl:if>
  </xsl:template>

</xsl:stylesheet>
