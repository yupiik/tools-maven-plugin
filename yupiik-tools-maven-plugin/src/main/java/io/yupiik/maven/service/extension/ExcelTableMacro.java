/*
 * Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.maven.service.extension;

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
