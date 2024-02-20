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
(window.bespoke = bespoke).deck = bespoke.from('.deck', [
  bespoke.plugins.classes(),
  bespoke.plugins.nav(),
  bespoke.plugins.fullscreen(),
  bespoke.plugins.scale('transform'),
  bespoke.plugins.overview({ margin: 300, autostart: false, title: true, numbers: true }),
  bespoke.plugins.bullets('.build,.build-items>*:not(.build-items)'),
  bespoke.plugins.title(),
  bespoke.plugins.hash(),
  bespoke.plugins.progress(),
]);

document.addEventListener('DOMContentLoaded', (event) => {
  hljs.registerLanguage('gherkin', function (hljs) {
    return {
      name: 'Gherkin',
      aliases: ['feature'],
      keywords: 'Feature Background Ability Business\ Need Scenario Scenarios Scenario\ Outline Scenario\ Template Rule Examples Example Given And Then But When',
      contains: [
        {
          className: 'symbol',
          begin: '\\*',
          relevance: 0
        },
        {
          className: 'meta',
          begin: '@[^@\\s]+'
        },
        {
          begin: '\\|',
          end: '\\|\\w*$',
          contains: [
            {
              className: 'string',
              begin: '[^|]+'
            }
          ]
        },
        {
          className: 'variable',
          begin: '<',
          end: '>'
        },
        hljs.HASH_COMMENT_MODE,
        {
          className: 'string',
          begin: '"""',
          end: '"""'
        },
        hljs.QUOTE_STRING_MODE
      ]
    };
  });

  hljs.highlightAll();
  setTimeout(function () {
    highlightJsBadge({ loadDelay: 0, copyIconClass: 'fa fa-copy', checkIconClass: 'fa fa-check text-success' });
  });
});
