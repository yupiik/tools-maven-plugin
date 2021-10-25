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
