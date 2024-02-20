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
import { useMemo } from "react";

export function Select({ id, className, items, attribute, setter }) {
    const proposals = useMemo(
        () => Object.keys(items.map(it => it[attribute]).reduce((a, i) => {
            a[i] = a;
            return a;
        }, {})).sort(),
        [items, attribute]);
    return (
        <div className={`form-floating mb-3 ${className || ''}`}>
            <select
                className="form-select" id={id}
                aria-label="Provider selection"
                onChange={({ target: { value } }) => setter(value)}>
                {proposals.map(it => (
                    <option key={it} value={it}>
                        {it || 'All'}
                    </option>))}
            </select>
            <label htmlFor={id}>Filter by {attribute}</label>
        </div>
    );
}