/*
 * Copyright (c) 2020 - Yupiik SAS - https://www.yupiik.com - All right reserved
 *
 * This software and related documentation are provided under a license agreement containing restrictions on use and
 * disclosure and are protected by intellectual property laws. Except as expressly permitted in your license agreement
 * or allowed by law, you may not use, copy, reproduce, translate, broadcast, modify, license, transmit, distribute,
 * exhibit, perform, publish, or display any part, in any form, or by any means. Reverse engineering, disassembly, or
 * decompilation of this software, unless required by law for interoperability, is prohibited.
 *
 * The information contained herein is subject to change without notice and is not warranted to be error-free. If you
 * find any errors, please report them to us in writing.
 *
 * This software is developed for general use in a variety of information management applications. It is not developed
 * or intended for use in any inherently dangerous applications, including applications that may create a risk of personal
 * injury. If you use this software or hardware in dangerous applications, then you shall be responsible to take all
 * appropriate fail-safe, backup, redundancy, and other measures to ensure its safe use. Yupiik SAS and its affiliates
 * disclaim any liability for any damages caused by use of this software or hardware in dangerous applications.
 *
 * Yupiik and Galaxy are registered trademarks of Yupiik SAS and/or its affiliates. Other names may be trademarks
 * of their respective owners.
 *
 * This software and documentation may provide access to or information about content, products, and services from third
 * parties. Yupiik SAS and its affiliates are not responsible for and expressly disclaim all warranties of any kind with
 * respect to third-party content, products, and services unless otherwise set forth in an applicable agreement between
 * you and Yupiik SAS. Yupiik SAS and its affiliates will not be responsible for any loss, costs, or damages incurred
 * due to your access to or use of third-party content, products, or services, except as set forth in an applicable
 * agreement between you and Yupiik SAS.
 */
package com.yupiik.maven.service.extension;

import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.asciidoctor.ast.ContentModel;
import org.asciidoctor.ast.StructuralNode;
import org.asciidoctor.extension.BlockProcessor;
import org.asciidoctor.extension.Contexts;
import org.asciidoctor.extension.Name;
import org.asciidoctor.extension.Reader;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.stream.StreamSupport;

import static java.util.Arrays.asList;
import static java.util.stream.Collectors.joining;

@Name("excel")
@Contexts(Contexts.OPEN)
@ContentModel(ContentModel.COMPOUND)
public class ExcelTableMacro extends BlockProcessor {
    @Override
    public Object process(final StructuralNode parent, final Reader target, final Map<String, Object> attributes) {
        try (final XSSFWorkbook wb = new XSSFWorkbook(new File(target.readLine().replace("{partialsdir}", String.valueOf(parent.getDocument().getAttribute("partialsdir"))))) ){
            final DataFormatter formatter = new DataFormatter();
            final FormulaEvaluator evaluator = wb.getCreationHelper().createFormulaEvaluator();
            final String sheetName = String.class.cast(attributes.get("sheet"));
            final Sheet sheet = sheetName == null ? wb.iterator().next() : wb.getSheet(sheetName);
            final String tableContent = StreamSupport.stream(sheet.spliterator(), false)
                    .map(row -> StreamSupport.stream(row.spliterator(), false)
                            .map(cell -> formatter.formatCellValue(cell, evaluator))
                            .collect(joining(";")))
                    .collect(joining("\n"));
            parseContent(parent, asList(("" +
                    "[opts=header,format=dsv,separator=;]\n" +
                    "|===\n" +
                    tableContent + '\n' +
                    "|===\n" +
                    "\n").split("\n")));
            return null;
        } catch (final IOException | InvalidFormatException e) {
            throw new IllegalStateException(e);
        }
    }
}
