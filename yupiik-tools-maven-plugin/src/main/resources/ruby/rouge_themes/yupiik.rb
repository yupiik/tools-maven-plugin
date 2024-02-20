#
# Copyright (c) 2020 - present - Yupiik SAS - https://www.yupiik.com
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#  http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
#


require 'rouge' unless defined? ::Rouge.version

module Rouge; module Themes
  # derived from igor_pro
  class Yupiik < CSSTheme
    name 'yupiik'

    style Text,                 :fg => '#444444', :bg => '#ffffff'
    style Comment::Preproc,     :fg => '#CC00A3'
    style Comment::Special,     :fg => '#CC00A3'
    style Comment,              :fg => '#999999'
    style Keyword::Constant,    :fg => '#C34E00'
    style Keyword::Declaration, :fg => '#0000FF'
    style Keyword::Reserved,    :fg => '#007575'
    style Keyword,              :fg => '#0000FF'
    style Literal::String,      :fg => '#009C00'
    style Name::Builtin,        :fg => '#C34E00'
    style Name::Decorator,      :fg => '#bf9000'
  end
end; end
