<?xml version="1.0" encoding="UTF-8"?>
<!--
  ~ Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com - All right reserved
  ~
  ~ This software and related documentation are provided under a license agreement containing restrictions on use and
  ~ disclosure and are protected by intellectual property laws. Except as expressly permitted in your license agreement
  ~ or allowed by law, you may not use, copy, reproduce, translate, broadcast, modify, license, transmit, distribute,
  ~ exhibit, perform, publish, or display any part, in any form, or by any means. Reverse engineering, disassembly, or
  ~ decompilation of this software, unless required by law for interoperability, is prohibited.
  ~
  ~ The information contained herein is subject to change without notice and is not warranted to be error-free. If you
  ~ find any errors, please report them to us in writing.
  ~
  ~ This software is developed for general use in a variety of information management applications. It is not developed
  ~ or intended for use in any inherently dangerous applications, including applications that may create a risk of personal
  ~ injury. If you use this software or hardware in dangerous applications, then you shall be responsible to take all
  ~ appropriate fail-safe, backup, redundancy, and other measures to ensure its safe use. Yupiik SAS and its affiliates
  ~ disclaim any liability for any damages caused by use of this software or hardware in dangerous applications.
  ~
  ~ Yupiik and Galaxy are registered trademarks of Yupiik SAS and/or its affiliates. Other names may be trademarks
  ~ of their respective owners.
  ~
  ~ This software and documentation may provide access to or information about content, products, and services from third
  ~ parties. Yupiik SAS and its affiliates are not responsible for and expressly disclaim all warranties of any kind with
  ~ respect to third-party content, products, and services unless otherwise set forth in an applicable agreement between
  ~ you and Yupiik SAS. Yupiik SAS and its affiliates will not be responsible for any loss, costs, or damages incurred
  ~ due to your access to or use of third-party content, products, or services, except as set forth in an applicable
  ~ agreement between you and Yupiik SAS.
  -->
<!--
Here for demo purposes, this is not complete at all.
-->
<xsl:stylesheet version="1.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">
  <xsl:output method="text"/>
  <xsl:template match="testcase">
    - <xsl:value-of select="@classname"/>.<xsl:value-of select="@name"/> lasted <xsl:value-of select="@time"/>s
  </xsl:template>
</xsl:stylesheet>