// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.openapi.editor.impl.view;

import com.intellij.openapi.editor.*;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.colors.EditorFontType;
import com.intellij.openapi.editor.colors.FontPreferences;
import com.intellij.openapi.editor.ex.MarkupModelEx;
import com.intellij.openapi.editor.ex.RangeHighlighterEx;
import com.intellij.openapi.editor.highlighter.EditorHighlighter;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.editor.impl.*;
import com.intellij.openapi.editor.impl.softwrap.SoftWrapDrawingType;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.editor.markup.TextAttributesEffectsBuilder.EffectDescriptor;
import com.intellij.openapi.util.Couple;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.openapi.wm.impl.IdeBackgroundUtil;
import com.intellij.ui.ColorUtil;
import com.intellij.ui.Gray;
import com.intellij.ui.JBColor;
import com.intellij.ui.paint.EffectPainter;
import com.intellij.ui.paint.LinePainter2D;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.DocumentUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.Processor;
import com.intellij.util.containers.PeekableIterator;
import com.intellij.util.containers.PeekableIteratorWrapper;
import com.intellij.util.text.CharArrayUtil;
import com.intellij.util.ui.UIUtil;
import gnu.trove.TFloatArrayList;
import gnu.trove.TIntObjectHashMap;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.*;
import java.util.List;
import java.util.*;

import static com.intellij.openapi.editor.markup.TextAttributesEffectsBuilder.EffectSlot.FRAME_SLOT;

/**
 * Renders editor contents.
 */
public class EditorPainter implements TextDrawingCallback {
  private static final Color CARET_LIGHT = Gray._255;
  private static final Color CARET_DARK = Gray._0;
  private static final Stroke IME_COMPOSED_TEXT_UNDERLINE_STROKE = new BasicStroke(1, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 0,
                                                                                   new float[]{0, 2, 0, 2}, 0);
  private static final int CARET_DIRECTION_MARK_SIZE = 5;
  private static final char IDEOGRAPHIC_SPACE = '\u3000'; // http://www.marathon-studios.com/unicode/U3000/Ideographic_Space
  private static final String WHITESPACE_CHARS = " \t" + IDEOGRAPHIC_SPACE;
  private static final Key<TextAttributes> INNER_HIGHLIGHTING = Key.create("inner.highlighting");

  private final EditorView myView;
  private final EditorImpl myEditor;
  private final Document myDocument;

  private XCorrector myCorrector;

  EditorPainter(EditorView view) {
    myView = view;
    myEditor = view.getEditor();
    myDocument = myEditor.getDocument();
    myCorrector = XCorrector.create(myView);
  }

  void paint(Graphics2D g) {
    myCorrector = myCorrector.align(myView);
    Rectangle clip = g.getClipBounds();

    if (myEditor.getContentComponent().isOpaque()) {
      g.setColor(myEditor.getBackgroundColor());
      g.fillRect(clip.x, clip.y, clip.width, clip.height);
    }

    if (paintPlaceholderText(g)) {
      paintCaret(g, 0);
      return;
    }

    int startLine = myView.yToVisualLine(clip.y);
    int endLine = myView.yToVisualLine(clip.y + clip.height - 1);
    int startOffset = myView.visualLineToOffset(startLine);
    int endOffset = myView.visualLineToOffset(endLine + 1);
    ClipDetector clipDetector = new ClipDetector(myEditor, clip);
    IterationState.CaretData caretData = myEditor.isPaintSelection() ? IterationState.createCaretData(myEditor) : null;
    TIntObjectHashMap<List<LineExtensionData>> extensionData = new TIntObjectHashMap<>(); // key is visual line

    int yShift = -clip.y;
    g.translate(0, -yShift);

    MarginPositions marginWidths = paintBackground(g, clip, yShift, startLine, endLine, caretData, extensionData);
    paintRightMargin(g, clip, marginWidths);
    paintCustomRenderers(g, yShift, startOffset, endOffset, clipDetector);
    MarkupModelEx docMarkup = myEditor.getFilteredDocumentMarkupModel();
    paintLineMarkersSeparators(g, clip, yShift, docMarkup, startOffset, endOffset);
    paintLineMarkersSeparators(g, clip, yShift, myEditor.getMarkupModel(), startOffset, endOffset);
    paintTextWithEffects(g, clip, yShift, startLine, endLine, caretData, extensionData);
    paintHighlightersAfterEndOfLine(g, yShift, docMarkup, startOffset, endOffset);
    paintHighlightersAfterEndOfLine(g, yShift, myEditor.getMarkupModel(), startOffset, endOffset);
    paintBorderEffect(g, clipDetector, yShift, myEditor.getHighlighter(), startOffset, endOffset);
    paintBorderEffect(g, clipDetector, yShift, docMarkup, startOffset, endOffset);
    paintBorderEffect(g, clipDetector, yShift, myEditor.getMarkupModel(), startOffset, endOffset);
    paintBlockInlays(g, clip, yShift, startLine, endLine);

    paintCaret(g, yShift);

    paintComposedTextDecoration(g, yShift);

    g.translate(0, yShift);
  }

  private boolean paintPlaceholderText(Graphics2D g) {
    CharSequence hintText = myEditor.getPlaceholder();
    EditorComponentImpl editorComponent = myEditor.getContentComponent();
    if (myDocument.getTextLength() > 0 || hintText == null || hintText.length() == 0 ||
        KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() == editorComponent &&
        !myEditor.getShowPlaceholderWhenFocused()) {
      return false;
    }

    hintText = SwingUtilities.layoutCompoundLabel(g.getFontMetrics(), hintText.toString(), null, 0, 0, 0, 0,
                                                  SwingUtilities.calculateInnerArea(editorComponent, null), // account for insets
                                                  new Rectangle(), new Rectangle(), 0);
    EditorFontType fontType = EditorFontType.PLAIN;
    Color color = myEditor.getFoldingModel().getPlaceholderAttributes().getForegroundColor();
    TextAttributes attributes = myEditor.getPlaceholderAttributes();
    if (attributes != null) {
      int type = attributes.getFontType();
      if (type == Font.ITALIC) fontType = EditorFontType.ITALIC;
      else if (type == Font.BOLD) fontType = EditorFontType.BOLD;
      else if (type == (Font.ITALIC | Font.BOLD)) fontType = EditorFontType.BOLD_ITALIC;

      Color attColor = attributes.getForegroundColor();
      if (attColor != null) color = attColor;
    }
    g.setColor(color);
    g.setFont(myEditor.getColorsScheme().getFont(fontType));
    Insets insets = myView.getInsets();
    g.drawString(hintText.toString(), insets.left, insets.top + myView.getAscent());
    return true;
  }

  private void paintRightMargin(Graphics g,
                                Rectangle clip,
                                MarginPositions marginWidths) {
    if (!isMarginShown()) return;
    g.setColor(myEditor.getColorsScheme().getColor(EditorColors.RIGHT_MARGIN_COLOR));
    float baseMarginWidth = getBaseMarginWidth(myView);
    int baseMarginX = myCorrector.marginX(baseMarginWidth);
    if (marginWidths == null) {
      LinePainter2D.paint((Graphics2D)g, baseMarginX, 0, baseMarginX, clip.height);
    }
    else {
      int lineHeight = myView.getLineHeight();
      int displayedLinesCount = marginWidths.x.length - 1;
      for(int i = 0; i <= displayedLinesCount; i++) {
        int y = marginWidths.y[i];
        int yStart = i == 0 ? 0 : y;
        int yEnd = i == displayedLinesCount ? clip.y + clip.height : y + lineHeight;
        float width = marginWidths.x[i];
        int x = width == 0 ? baseMarginX : (int) width;
        g.fillRect(x, yStart, 1,  yEnd - yStart);
        if (i < displayedLinesCount) {
          float nextWidth = marginWidths.x[i + 1];
          int nextX = nextWidth == 0 ? baseMarginX : (int)nextWidth;
          if (nextX != x) g.fillRect(Math.min(x, nextX), y + lineHeight - 1, Math.abs(x - nextX) + 1, 1);
        }
      }
    }
    Color visualGuidesColor = myEditor.getColorsScheme().getColor(EditorColors.VISUAL_INDENT_GUIDE_COLOR);
    if (visualGuidesColor != null) {
      g.setColor(visualGuidesColor);
      for (Integer marginX : myCorrector.softMarginsX()) {
        LinePainter2D.paint((Graphics2D)g, marginX, 0, marginX, clip.height);
      }
    }
  }

  private static float getBaseMarginWidth(EditorView view) {
    Editor editor = view.getEditor();
    return editor.getSettings().getRightMargin(editor.getProject()) * view.getPlainSpaceWidth();
  }

  private boolean isMarginShown() {
    return isMarginShown(myEditor);
  }

  public static boolean isMarginShown(@NotNull Editor editor) {
    return editor.getSettings().isRightMarginShown() &&
           editor.getColorsScheme().getColor(EditorColors.RIGHT_MARGIN_COLOR) != null &&
           (Registry.is("editor.show.right.margin.in.read.only.files") || editor.getDocument().isWritable());
  }

  private MarginPositions paintBackground(Graphics2D g, Rectangle clip, int yShift, int startVisualLine, int endVisualLine,
                                          IterationState.CaretData caretData, TIntObjectHashMap<List<LineExtensionData>> extensionData) {
    int lineCount = myEditor.getVisibleLineCount();
    boolean calculateMarginWidths = Registry.is("editor.adjust.right.margin") && isMarginShown() && startVisualLine < lineCount;
    MarginPositions marginWidths = calculateMarginWidths ? new MarginPositions(Math.min(endVisualLine, lineCount - 1) - startVisualLine + 2)
                                                         : null;
    int maxVisualLine = endVisualLine + (calculateMarginWidths ? 1 : 0);

    final Map<Integer, Couple<Integer>> virtualSelectionMap = createVirtualSelectionMap(startVisualLine, endVisualLine);
    final VisualPosition primarySelectionStart = myEditor.getSelectionModel().getSelectionStartPosition();
    final VisualPosition primarySelectionEnd = myEditor.getSelectionModel().getSelectionEndPosition();

    LineLayout prefixLayout = myView.getPrefixLayout();
    if (startVisualLine == 0 && prefixLayout != null) {
      float width = prefixLayout.getWidth();
      paintBackground(g, myView.getPrefixAttributes(), myCorrector.startX(startVisualLine), yShift + myView.visualLineToY(0), width);
    }

    VisualLinesIterator visLinesIterator = new VisualLinesIterator(myEditor, startVisualLine);
    while (!visLinesIterator.atEnd()) {
      int visualLine = visLinesIterator.getVisualLine();
      if (visualLine > maxVisualLine) break;
      int y = visLinesIterator.getY() + yShift;
      if (calculateMarginWidths) marginWidths.y[visualLine - startVisualLine] = y;
      boolean dryRun = visualLine > endVisualLine;
      paintLineFragments(g, clip, visLinesIterator, caretData, y, new LineFragmentPainter() {
        @Override
        public void paintBeforeLineStart(Graphics2D g, TextAttributes attributes, boolean hasSoftWrap, int columnEnd, float xEnd, int y) {
          if (dryRun) return;
          paintBackground(g, attributes, myView.getInsets().left, y, xEnd);
          if (!hasSoftWrap) return;
          paintSelectionOnSecondSoftWrapLineIfNecessary(g, visualLine, columnEnd, xEnd, y, primarySelectionStart, primarySelectionEnd);
        }

        @Override
        public void paint(Graphics2D g, VisualLineFragmentsIterator.Fragment fragment, int start, int end,
                          TextAttributes attributes, float xStart, float xEnd, int y) {
          if (dryRun) return;
          FoldRegion foldRegion = fragment.getCurrentFoldRegion();
          if (foldRegion != null && Registry.is("editor.highlight.foldings")) {
            paintFoldingBackground(g, attributes, xStart, y, xEnd - xStart, foldRegion);
          }
          else {
            paintBackground(g, attributes, xStart, y, xEnd - xStart);
          }
        }

        @Override
        public void paintAfterLineEnd(Graphics2D g, Rectangle clip, IterationState it, int columnStart, float x, int y) {
          if (dryRun) return;
          paintBackground(g, it.getPastLineEndBackgroundAttributes(), x, y, clip.x + clip.width - x);
          int offset = it.getEndOffset();
          SoftWrap softWrap = myEditor.getSoftWrapModel().getSoftWrap(offset);
          if (softWrap == null) {
            collectExtensions(visualLine, offset, extensionData);
            paintLineExtensionsBackground(g, visualLine, x, y, extensionData);
            paintVirtualSelectionIfNecessary(g, visualLine, virtualSelectionMap, columnStart, x, clip.x + clip.width, y);
          }
          else {
            paintSelectionOnFirstSoftWrapLineIfNecessary(g, visualLine, columnStart, x, clip.x + clip.width, y,
                                                         primarySelectionStart, primarySelectionEnd);
          }
        }
      }, calculateMarginWidths && !visLinesIterator.endsWithSoftWrap() && !visLinesIterator.startsWithSoftWrap()
         ? width -> marginWidths.x[visualLine - startVisualLine] = width : null);
      visLinesIterator.advance();
    }
    if (calculateMarginWidths && endVisualLine >= lineCount - 1) {
      marginWidths.y[marginWidths.y.length - 1] = marginWidths.y[marginWidths.y.length - 2] + myView.getLineHeight();
    }
    return marginWidths;
  }

  private void paintFoldingBackground(Graphics2D g, TextAttributes attributes, float x, int y, float width, @NotNull FoldRegion foldRegion) {
    TextAttributes innerAttributes = getInnerHighlighterAttributes(foldRegion);
    if (innerAttributes != null) {
      foldRegion.putUserData(INNER_HIGHLIGHTING, innerAttributes);
      if (innerAttributes.getBackgroundColor() != null && !isSelected(foldRegion)) {
        paintBackground(g, innerAttributes, x, y, width);
        Color borderColor = myEditor.getColorsScheme().getColor(EditorColors.FOLDED_TEXT_BORDER_COLOR);
        if (borderColor != null) {
          Shape border = getBorderShape(x, y, width, myView.getLineHeight(), 2, false);
          if (border != null) {
            g.setColor(borderColor);
            g.fill(border);
          }
        }
        return;
      }
    }
    paintBackground(g, attributes, x, y, width);
  }

  private Map<Integer, Couple<Integer>> createVirtualSelectionMap(int startVisualLine, int endVisualLine) {
    HashMap<Integer, Couple<Integer>> map = new HashMap<>();
    for (Caret caret : myEditor.getCaretModel().getAllCarets()) {
      if (caret.hasSelection()) {
        VisualPosition selectionStart = caret.getSelectionStartPosition();
        VisualPosition selectionEnd = caret.getSelectionEndPosition();
        if (selectionStart.line == selectionEnd.line) {
          int line = selectionStart.line;
          if (line >= startVisualLine && line <= endVisualLine) {
            map.put(line, Couple.of(selectionStart.column, selectionEnd.column));
          }
        }
      }
    }
    return map;
  }

  private void paintVirtualSelectionIfNecessary(Graphics2D g,
                                                int visualLine,
                                                Map<Integer, Couple<Integer>> virtualSelectionMap,
                                                int columnStart,
                                                float xStart,
                                                float xEnd,
                                                int y) {
    Couple<Integer> selectionRange = virtualSelectionMap.get(visualLine);
    if (selectionRange == null || selectionRange.second <= columnStart) return;
    float startX = selectionRange.first <= columnStart ? xStart :
                   (float)myView.visualPositionToXY(new VisualPosition(visualLine, selectionRange.first)).getX();
    float endX = (float)Math.min(xEnd, myView.visualPositionToXY(new VisualPosition(visualLine, selectionRange.second)).getX());
    paintBackground(g, myEditor.getColorsScheme().getColor(EditorColors.SELECTION_BACKGROUND_COLOR), startX, y, endX - startX);
  }

  private void paintSelectionOnSecondSoftWrapLineIfNecessary(Graphics2D g, int visualLine, int columnEnd, float xEnd, int y,
                                                             VisualPosition selectionStartPosition, VisualPosition selectionEndPosition) {
    if (selectionStartPosition.equals(selectionEndPosition) ||
        visualLine < selectionStartPosition.line ||
        visualLine > selectionEndPosition.line ||
        visualLine == selectionStartPosition.line && selectionStartPosition.column >= columnEnd) {
      return;
    }

    float startX = (selectionStartPosition.line == visualLine && selectionStartPosition.column > 0) ?
                   (float)myView.visualPositionToXY(selectionStartPosition).getX() : myCorrector.startX(visualLine);
    float endX = (selectionEndPosition.line == visualLine && selectionEndPosition.column < columnEnd) ?
                 (float)myView.visualPositionToXY(selectionEndPosition).getX() : xEnd;

    paintBackground(g, myEditor.getColorsScheme().getColor(EditorColors.SELECTION_BACKGROUND_COLOR), startX, y, endX - startX);
  }

  private void paintSelectionOnFirstSoftWrapLineIfNecessary(Graphics2D g, int visualLine, int columnStart, float xStart, float xEnd, int y,
                                                            VisualPosition selectionStartPosition, VisualPosition selectionEndPosition) {
    if (selectionStartPosition.equals(selectionEndPosition) ||
        visualLine < selectionStartPosition.line ||
        visualLine > selectionEndPosition.line ||
        visualLine == selectionEndPosition.line && selectionEndPosition.column <= columnStart) {
      return;
    }

    float startX = selectionStartPosition.line == visualLine && selectionStartPosition.column > columnStart ?
                   (float)myView.visualPositionToXY(selectionStartPosition).getX() : xStart;
    float endX = selectionEndPosition.line == visualLine ?
                 (float)myView.visualPositionToXY(selectionEndPosition).getX() : xEnd;

    paintBackground(g, myEditor.getColorsScheme().getColor(EditorColors.SELECTION_BACKGROUND_COLOR), startX, y, endX - startX);
  }

  private void paintBackground(Graphics2D g, TextAttributes attributes, float x, int y, float width) {
    if (attributes == null) return;
    paintBackground(g, attributes.getBackgroundColor(), x, y, width);
  }

  private void paintBackground(Graphics2D g, Color color, float x, int y, float width) {
    paintBackground(g, color, x, y, width, myView.getLineHeight());
  }

  private void paintBackground(Graphics2D g, Color color, float x, int y, float width, int height) {
    if (width <= 0 ||
        color == null ||
        color.equals(myEditor.getColorsScheme().getDefaultBackground()) ||
        color.equals(myEditor.getBackgroundColor())) return;
    g.setColor(color);
    g.fill(new Rectangle2D.Float(x, y, width, height));
  }

  private void paintCustomRenderers(final Graphics2D g, int yShift, final int startOffset, final int endOffset, ClipDetector clipDetector) {
    g.translate(0, yShift);
    myEditor.getMarkupModel().processRangeHighlightersOverlappingWith(startOffset, endOffset, highlighter -> {
      CustomHighlighterRenderer customRenderer = highlighter.getCustomRenderer();
      if (customRenderer != null) {
        int highlighterStart = highlighter.getStartOffset();
        int highlighterEnd = highlighter.getEndOffset();
        if (highlighterStart <= endOffset && highlighterEnd >= startOffset &&
            clipDetector.rangeCanBeVisible(highlighterStart, highlighterEnd)) {
          customRenderer.paint(myEditor, highlighter, g);
        }
      }
      return true;
    });
    g.translate(0, -yShift);
  }

  private void paintLineMarkersSeparators(final Graphics g,
                                          final Rectangle clip,
                                          int yShift,
                                          MarkupModelEx markupModel,
                                          int startOffset,
                                          int endOffset) {
    // we decrement startOffset to capture also line-range highlighters on the previous line,
    // cause they can render a separator visible on current line
    markupModel.processRangeHighlightersOverlappingWith(startOffset - 1, endOffset, highlighter -> {
      paintLineMarkerSeparator(highlighter, clip, g, yShift);
      return true;
    });
  }

  private void paintLineMarkerSeparator(RangeHighlighter marker, Rectangle clip, Graphics g, int yShift) {
    Color separatorColor = marker.getLineSeparatorColor();
    LineSeparatorRenderer lineSeparatorRenderer = marker.getLineSeparatorRenderer();
    if (separatorColor == null && lineSeparatorRenderer == null) {
      return;
    }
    boolean isTop = marker.getLineSeparatorPlacement() == SeparatorPlacement.TOP;
    int edgeOffset = isTop ? myDocument.getLineStartOffset(myDocument.getLineNumber(marker.getStartOffset()))
                           : myDocument.getLineEndOffset(myDocument.getLineNumber(marker.getEndOffset()));
    int visualLine = myView.offsetToVisualLine(edgeOffset, !isTop);
    int y = myView.visualLineToY(visualLine) + (isTop ? 0 : myView.getLineHeight()) - 1 + yShift;
    int startX = myCorrector.lineSeparatorStart(clip.x);
    int endX = myCorrector.lineSeparatorEnd(clip.x + clip.width);
    g.setColor(separatorColor);
    if (lineSeparatorRenderer != null) {
      lineSeparatorRenderer.drawLine(g, startX, endX, y);
    }
    else {
      LinePainter2D.paint((Graphics2D)g, startX, y, endX, y);
    }
  }

  private void paintTextWithEffects(Graphics2D g, Rectangle clip, int yShift, int startVisualLine, int endVisualLine,
                                    IterationState.CaretData caretData, TIntObjectHashMap<List<LineExtensionData>> extensionData) {
    final CharSequence text = myDocument.getImmutableCharSequence();
    final LineWhitespacePaintingStrategy whitespacePaintingStrategy = new LineWhitespacePaintingStrategy(myEditor.getSettings());
    boolean paintAllSoftWraps = myEditor.getSettings().isAllSoftWrapsShown();
    int lineCount = myEditor.getVisibleLineCount();
    float whiteSpaceScale = ((float) myEditor.getColorsScheme().getEditorFontSize()) / FontPreferences.DEFAULT_FONT_SIZE;
    final BasicStroke whiteSpaceStroke = new BasicStroke(calcFeatureSize(1, whiteSpaceScale));

    LineLayout prefixLayout = myView.getPrefixLayout();
    if (startVisualLine == 0 && prefixLayout != null) {
      TextAttributes attributes = myView.getPrefixAttributes();
      g.setColor(attributes.getForegroundColor());
      paintLineLayoutWithEffect(g, prefixLayout, myCorrector.startX(startVisualLine), myView.getAscent() + yShift + myView.visualLineToY(0),
                                attributes.getEffectColor(), attributes.getEffectType());
    }

    VisualLinesIterator visLinesIterator = new VisualLinesIterator(myEditor, startVisualLine);
    while (!visLinesIterator.atEnd()) {
      int visualLine = visLinesIterator.getVisualLine();
      if (visualLine > endVisualLine || visualLine >= lineCount) break;

      int y = visLinesIterator.getY() + yShift;
      final boolean paintSoftWraps = paintAllSoftWraps ||
                                     myEditor.getCaretModel().getLogicalPosition().line == visLinesIterator.getStartLogicalLine();
      final int[] currentLogicalLine = new int[] {-1};

      paintLineFragments(g, clip, visLinesIterator, caretData, y + myView.getAscent(), new LineFragmentPainter() {
        @Override
        public void paintBeforeLineStart(Graphics2D g, TextAttributes attributes, boolean hasSoftWrap, int columnEnd, float xEnd, int y) {
          if (paintSoftWraps && hasSoftWrap) {
            SoftWrapModelImpl softWrapModel = myEditor.getSoftWrapModel();
            int symbolWidth = softWrapModel.getMinDrawingWidthInPixels(SoftWrapDrawingType.AFTER_SOFT_WRAP);
            softWrapModel.doPaint(g, SoftWrapDrawingType.AFTER_SOFT_WRAP,
                                  (int)xEnd - symbolWidth, y - myView.getAscent(), myView.getLineHeight());
          }
        }

        @Override
        public void paint(Graphics2D g, VisualLineFragmentsIterator.Fragment fragment, int start, int end,
                          TextAttributes attributes, float xStart, float xEnd, int y) {
          int lineHeight = myView.getLineHeight();
          Inlay inlay = fragment.getCurrentInlay();
          if (inlay != null) {
            inlay.getRenderer().paint(inlay, g,
                                      new Rectangle((int) xStart, y - myView.getAscent(), inlay.getWidthInPixels(), lineHeight),
                                      attributes);
            return;
          }
          FoldRegion foldRegion = fragment.getCurrentFoldRegion();
          if (foldRegion != null && Registry.is("editor.highlight.foldings")) {
            attributes = getFoldingInnerAttributes(attributes, foldRegion);
          }
          if (attributes != null) {
            attributes.forEachEffect((type, color) -> paintTextEffect(g, xStart, xEnd, y, color, type, foldRegion != null));
          }
          if (attributes != null && attributes.getForegroundColor() != null) {
            g.setColor(attributes.getForegroundColor());
            fragment.draw(g, xStart, y, start, end);
          }
          if (foldRegion == null) {
            int logicalLine = fragment.getStartLogicalLine();
            if (logicalLine != currentLogicalLine[0]) {
              whitespacePaintingStrategy.update(text, myDocument.getLineStartOffset(logicalLine), myDocument.getLineEndOffset(logicalLine));
              currentLogicalLine[0] = logicalLine;
            }
            paintWhitespace(g, text, xStart, y, start, end, fragment.getStartLogicalColumn(), whitespacePaintingStrategy, fragment,
                            whiteSpaceStroke, whiteSpaceScale);
          }
        }

        @Override
        public void paintAfterLineEnd(Graphics2D g, Rectangle clip, IterationState iterationState, int columnStart, float x, int y) {
          int offset = iterationState.getEndOffset();
          SoftWrapModelImpl softWrapModel = myEditor.getSoftWrapModel();
          if (softWrapModel.getSoftWrap(offset) == null) {
            int logicalLine = myDocument.getLineNumber(offset);
            List<Inlay> inlays = myEditor.getInlayModel().getAfterLineEndElementsForLogicalLine(logicalLine);
            if (!inlays.isEmpty()) {
              x += myView.getPlainSpaceWidth();
              int lineHeight = myView.getLineHeight();
              TextAttributes backgroundAttributes = iterationState.getPastLineEndBackgroundAttributes();
              for (Inlay inlay : inlays) {
                int width = inlay.getWidthInPixels();
                inlay.getRenderer().paint(inlay, g, new Rectangle((int) x, y - myView.getAscent(), width, lineHeight),
                                          backgroundAttributes);
                x += width;
              }
            }
            paintLineExtensions(g, visualLine, logicalLine, x, y, extensionData);
          }
          else if (paintSoftWraps) {
            softWrapModel.doPaint(g, SoftWrapDrawingType.BEFORE_SOFT_WRAP_LINE_FEED,
                                  (int)x, y - myView.getAscent(), myView.getLineHeight());
          }
        }
      }, null);
      visLinesIterator.advance();
    }
    ComplexTextFragment.flushDrawingCache(g);
  }

  private static TextAttributes getFoldingInnerAttributes(TextAttributes basicAttributes, FoldRegion foldRegion) {
    TextAttributes innerAttributes = foldRegion.getUserData(INNER_HIGHLIGHTING);
    if (innerAttributes != null) {
      basicAttributes = TextAttributes.merge(basicAttributes, innerAttributes);
      foldRegion.putUserData(INNER_HIGHLIGHTING, null);
    }
    return basicAttributes;
  }

  @Nullable
  private TextAttributes getInnerHighlighterAttributes(@NotNull FoldRegion region) {
    if (region.areInnerHighlightersMuted()) return null;
    List<RangeHighlighterEx> innerHighlighters = new ArrayList<>();
    collectVisibleInnerHighlighters(region, myEditor.getMarkupModel(), innerHighlighters);
    collectVisibleInnerHighlighters(region, myEditor.getFilteredDocumentMarkupModel(), innerHighlighters);
    if (innerHighlighters.isEmpty()) return null;
    innerHighlighters.sort(IterationState.BY_LAYER_THEN_ATTRIBUTES);
    Color fgColor = null;
    Color bgColor = null;
    Color effectColor = null;
    EffectType effectType = null;
    for (RangeHighlighter h : innerHighlighters) {
      TextAttributes attrs = h.getTextAttributes();
      if (attrs == null) continue;
      if (fgColor == null && attrs.getForegroundColor() != null) fgColor = attrs.getForegroundColor();
      if (bgColor == null && attrs.getBackgroundColor() != null) bgColor = attrs.getBackgroundColor();
      if (effectColor == null && attrs.getEffectColor() != null) {
        EffectType type = attrs.getEffectType();
        if (type != null && type != EffectType.BOXED && type != EffectType.ROUNDED_BOX && type != EffectType.STRIKEOUT) {
          effectColor = attrs.getEffectColor();
          effectType = type;
        }
      }
    }
    return new TextAttributes(fgColor, bgColor, effectColor, effectType, Font.PLAIN);
  }

  private static void collectVisibleInnerHighlighters(@NotNull FoldRegion region, @NotNull MarkupModelEx markupModel,
                                                      @NotNull List<? super RangeHighlighterEx> highlighters) {
    int startOffset = region.getStartOffset();
    int endOffset = region.getEndOffset();
    markupModel.processRangeHighlightersOverlappingWith(startOffset, endOffset, h -> {
      if (h.isVisibleIfFolded() && h.getAffectedAreaStartOffset() >= startOffset && h.getAffectedAreaEndOffset() <= endOffset) {
        highlighters.add(h);
      }
      return true;
    });
  }

  private float paintLineLayoutWithEffect(Graphics2D g, LineLayout layout, float x, float y,
                                  @Nullable Color effectColor, @Nullable EffectType effectType) {
    paintTextEffect(g, x, x + layout.getWidth(), (int)y, effectColor, effectType, false);
    for (LineLayout.VisualFragment fragment : layout.getFragmentsInVisualOrder(x)) {
      fragment.draw(g, fragment.getStartX(), y);
      x = fragment.getEndX();
    }
    return x;
  }

  private void paintTextEffect(@NotNull Graphics2D g, float xFrom, float xTo, int y, @Nullable Color effectColor, @Nullable EffectType effectType, boolean allowBorder) {
    if (effectColor == null) {
      return;
    }
    g.setColor(effectColor);
    int xStart = (int)xFrom;
    int xEnd = (int)xTo;
    if (effectType == EffectType.LINE_UNDERSCORE) {
      EffectPainter.LINE_UNDERSCORE.paint(g, xStart, y, xEnd - xStart, myView.getDescent(),
                                          myEditor.getColorsScheme().getFont(EditorFontType.PLAIN));
    }
    else if (effectType == EffectType.BOLD_LINE_UNDERSCORE) {
      EffectPainter.BOLD_LINE_UNDERSCORE.paint(g, xStart, y, xEnd - xStart, myView.getDescent(),
                                               myEditor.getColorsScheme().getFont(EditorFontType.PLAIN));
    }
    else if (effectType == EffectType.STRIKEOUT) {
      EffectPainter.STRIKE_THROUGH.paint(g, xStart, y, xEnd - xStart, myView.getCharHeight(),
                                         myEditor.getColorsScheme().getFont(EditorFontType.PLAIN));
    }
    else if (effectType == EffectType.WAVE_UNDERSCORE) {
      EffectPainter.WAVE_UNDERSCORE.paint(g, xStart, y, xEnd - xStart, myView.getDescent(),
                                          myEditor.getColorsScheme().getFont(EditorFontType.PLAIN));
    }
    else if (effectType == EffectType.BOLD_DOTTED_LINE) {
      EffectPainter.BOLD_DOTTED_UNDERSCORE.paint(g, xStart, y, xEnd - xStart, myView.getDescent(),
                                                 myEditor.getColorsScheme().getFont(EditorFontType.PLAIN));
    }
    else if (allowBorder && (effectType == EffectType.BOXED || effectType == EffectType.ROUNDED_BOX)) {
      drawSimpleBorder(g, xFrom, xTo, y - myView.getAscent(), effectType == EffectType.ROUNDED_BOX);
    }
  }

  private static int calcFeatureSize(int unscaledSize, float scale) {
    return Math.max(1, Math.round(scale * unscaledSize));
  }

  private void paintWhitespace(Graphics2D g, CharSequence text, float x, int y, int start, int end, int startLogicalColumn,
                               LineWhitespacePaintingStrategy whitespacePaintingStrategy,
                               VisualLineFragmentsIterator.Fragment fragment, BasicStroke stroke, float scale) {
    if (!whitespacePaintingStrategy.showAnyWhitespace()) return;

    Stroke oldStroke = g.getStroke();
    Object oldHint = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
    g.setColor(myEditor.getColorsScheme().getColor(EditorColors.WHITESPACES_COLOR));

    boolean isRtl = fragment.isRtl();
    int baseStartOffset = fragment.getStartOffset();
    int startOffset = isRtl ? baseStartOffset - start : baseStartOffset + start;
    y -= 1;

    for (int i = start; i < end; i++) {
      int charOffset = isRtl ? baseStartOffset - i - 1 : baseStartOffset + i;
      char c = text.charAt(charOffset);
      if (" \t\u3000".indexOf(c) >= 0 && whitespacePaintingStrategy.showWhitespaceAtOffset(charOffset)) {
        int startX = (int)fragment.offsetToX(x, startOffset, isRtl ? baseStartOffset - i : baseStartOffset + i);
        int endX = (int)fragment.offsetToX(x, startOffset, isRtl ? baseStartOffset - i - 1 : baseStartOffset + i + 1);

        if (c == ' ') {
          int lineHeight = myView.getLineHeight();
          int ascent = myView.getAscent();
          int tabSize = myView.getTabSize();
          boolean bold = whitespacePaintingStrategy.isAdvancedHighlighting(charOffset) && (startLogicalColumn + i + 1) % tabSize == 0;
          float size = (bold ? 3 : 2) * scale;
          g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
          // making center point lie exactly between pixels
          //noinspection IntegerDivisionInFloatingPointContext
          g.fill(new Ellipse2D.Float((startX + endX)/2 - size/2, y + 1 - ascent + lineHeight/2 - size/2, size, size));
        }
        else if (c == '\t') {
          int tabLineHeight = calcFeatureSize(4, scale);
          int tabLineWidth = Math.min(endX - startX, calcFeatureSize(3, scale));
          startX = Math.min(endX - tabLineWidth, startX + tabLineWidth);
          g.setStroke(stroke);
          g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
          g.drawLine(startX, y, startX + tabLineWidth, y - tabLineHeight);
          g.drawLine(startX, y - tabLineHeight * 2, startX + tabLineWidth, y - tabLineHeight);
        }
        else if (c == '\u3000') { // ideographic space
          int charHeight = myView.getCharHeight();
          int strokeWidth = Math.round(stroke.getLineWidth());
          g.setStroke(stroke);
          g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
          g.drawRect(startX + JBUIScale.scale(2) + strokeWidth / 2, y - charHeight + strokeWidth / 2,
                     endX - startX - JBUIScale.scale(4) - (strokeWidth - 1), charHeight - (strokeWidth - 1));
        }
      }
    }
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, oldHint);
    g.setStroke(oldStroke);
  }

  private void collectExtensions(int visualLine, int offset, TIntObjectHashMap<List<LineExtensionData>> extensionData) {
    myEditor.processLineExtensions(myDocument.getLineNumber(offset), (info) -> {
      List<LineExtensionData> list = extensionData.get(visualLine);
      if (list == null) extensionData.put(visualLine, list = new ArrayList<>());
      list.add(new LineExtensionData(info, LineLayout.create(myView, info.getText(), info.getFontType())));
      return true;
    });
  }

  private void paintLineExtensionsBackground(Graphics2D g, int visualLine, float x, int y,
                                             TIntObjectHashMap<List<LineExtensionData>> extensionData) {
    List<LineExtensionData> data = extensionData.get(visualLine);
    if (data == null) return;
    for (LineExtensionData datum : data) {
      float width = datum.layout.getWidth();
      paintBackground(g, datum.info.getBgColor(), x, y, width);
      x += width;
    }
  }

  private void paintLineExtensions(Graphics2D g, int visualLine, int logicalLine, float x, int y,
                                   TIntObjectHashMap<List<LineExtensionData>> extensionData) {
    List<LineExtensionData> data = extensionData.get(visualLine);
    if (data == null) return;
    for (LineExtensionData datum : data) {
      g.setColor(datum.info.getColor());
      x = paintLineLayoutWithEffect(g, datum.layout, x, y, datum.info.getEffectColor(), datum.info.getEffectType());
    }
    int currentLineWidth = myCorrector.lineWidth(visualLine, x);
    EditorSizeManager sizeManager = myView.getSizeManager();
    if (currentLineWidth > sizeManager.getMaxLineWithExtensionWidth()) {
      sizeManager.setMaxLineWithExtensionWidth(logicalLine, currentLineWidth);
      myEditor.getContentComponent().revalidate();
    }
  }

  private void paintHighlightersAfterEndOfLine(final Graphics2D g,
                                               int yShift,
                                               MarkupModelEx markupModel,
                                               final int startOffset,
                                               int endOffset) {
    markupModel.processRangeHighlightersOverlappingWith(startOffset, endOffset, highlighter -> {
      if (highlighter.getStartOffset() >= startOffset) {
        paintHighlighterAfterEndOfLine(g, yShift, highlighter);
      }
      return true;
    });
  }

  private void paintHighlighterAfterEndOfLine(Graphics2D g, int yShift, RangeHighlighterEx highlighter) {
    if (!highlighter.isAfterEndOfLine()) {
      return;
    }
    int startOffset = highlighter.getStartOffset();
    int lineEndOffset = myDocument.getLineEndOffset(myDocument.getLineNumber(startOffset));
    if (myEditor.getFoldingModel().isOffsetCollapsed(lineEndOffset)) return;
    Point2D lineEnd = myView.offsetToXY(lineEndOffset, true, false);
    float x = (float)lineEnd.getX();
    int y = (int)lineEnd.getY() + yShift;
    TextAttributes attributes = highlighter.getTextAttributes();
    paintBackground(g, attributes, x, y, myView.getPlainSpaceWidth());
    if (attributes != null) {
      attributes.forEachEffect(
        (type, color) -> paintTextEffect(g, x, x + myView.getPlainSpaceWidth() - 1, y + myView.getAscent(), color, type, false));
    }
  }

  private void paintBorderEffect(Graphics2D g,
                                 ClipDetector clipDetector,
                                 int yShift,
                                 EditorHighlighter highlighter,
                                 int clipStartOffset,
                                 int clipEndOffset) {
    HighlighterIterator it = highlighter.createIterator(clipStartOffset);
    while (!it.atEnd() && it.getStart() < clipEndOffset) {
      TextAttributes attributes = it.getTextAttributes();
      EffectDescriptor borderDescriptor = getBorderDescriptor(attributes);
      if (borderDescriptor != null) {
        paintBorderEffect(g, clipDetector, yShift, it.getStart(), it.getEnd(), borderDescriptor);
      }
      it.advance();
    }
  }

  private void paintBorderEffect(final Graphics2D g,
                                 final ClipDetector clipDetector,
                                 int yShift,
                                 MarkupModelEx markupModel,
                                 int clipStartOffset,
                                 int clipEndOffset) {
    markupModel.processRangeHighlightersOverlappingWith(clipStartOffset, clipEndOffset, rangeHighlighter -> {
      TextAttributes attributes = rangeHighlighter.getTextAttributes();
      EffectDescriptor borderDescriptor = getBorderDescriptor(attributes);
      if (borderDescriptor != null) {
        paintBorderEffect(g, clipDetector, yShift,
                          rangeHighlighter.getAffectedAreaStartOffset(), rangeHighlighter.getAffectedAreaEndOffset(), borderDescriptor);
      }
      return true;
    });
  }

  /**
   * @return {@link EffectDescriptor descriptor} of border effect if attributes contains a border effect with not null color. Null otherwise
   */
  @Contract("null -> null")
  @Nullable
  private static EffectDescriptor getBorderDescriptor(@Nullable TextAttributes attributes) {
    return attributes == null || !attributes.hasEffects() ? null :
           TextAttributesEffectsBuilder.create(attributes).getEffectDescriptor(FRAME_SLOT);
  }

  private void paintBorderEffect(Graphics2D g, ClipDetector clipDetector, int yShift,
                                 int startOffset, int endOffset, EffectDescriptor borderDescriptor) {
    startOffset = DocumentUtil.alignToCodePointBoundary(myDocument, startOffset);
    endOffset = DocumentUtil.alignToCodePointBoundary(myDocument, endOffset);
    if (!clipDetector.rangeCanBeVisible(startOffset, endOffset)) return;
    int startLine = myDocument.getLineNumber(startOffset);
    int endLine = myDocument.getLineNumber(endOffset);
    if (startLine + 1 == endLine &&
        startOffset == myDocument.getLineStartOffset(startLine) &&
        endOffset == myDocument.getLineStartOffset(endLine)) {
      // special case of line highlighters
      endLine--;
      endOffset = myDocument.getLineEndOffset(endLine);
    }

    boolean rounded = borderDescriptor.effectType == EffectType.ROUNDED_BOX;
    g.setColor(borderDescriptor.effectColor);
    VisualPosition startPosition = myView.offsetToVisualPosition(startOffset, true, false);
    VisualPosition endPosition = myView.offsetToVisualPosition(endOffset, false, true);
    if (startPosition.line == endPosition.line) {
      int y = myView.visualLineToY(startPosition.line) + yShift;
      TFloatArrayList ranges = adjustedLogicalRangeToVisualRanges(startOffset, endOffset);
      for (int i = 0; i < ranges.size() - 1; i+= 2) {
        float startX = myCorrector.singleLineBorderStart(ranges.get(i));
        float endX = myCorrector.singleLineBorderEnd(ranges.get(i + 1));
        drawSimpleBorder(g, startX, endX, y, rounded);
      }
    }
    else {
      TFloatArrayList leadingRanges = adjustedLogicalRangeToVisualRanges(
        startOffset, myView.visualPositionToOffset(new VisualPosition(startPosition.line, Integer.MAX_VALUE, true)));
      TFloatArrayList trailingRanges = adjustedLogicalRangeToVisualRanges(
        myView.visualPositionToOffset(new VisualPosition(endPosition.line, 0)), endOffset);
      if (!leadingRanges.isEmpty() && !trailingRanges.isEmpty()) {
        int minX = Math.min(myCorrector.minX(startPosition.line, endPosition.line), (int)leadingRanges.get(0));
        int maxX = Math.max(myCorrector.maxX(startPosition.line, endPosition.line), (int)trailingRanges.get(trailingRanges.size() - 1));
        boolean containsInnerLines = endPosition.line > startPosition.line + 1;
        int lineHeight = myView.getLineHeight() - 1;
        int leadingTopY = myView.visualLineToY(startPosition.line) + yShift;
        int leadingBottomY = leadingTopY + lineHeight;
        int trailingTopY = myView.visualLineToY(endPosition.line) + yShift;
        int trailingBottomY = trailingTopY + lineHeight;
        float start = 0;
        float end = 0;
        float leftGap = leadingRanges.get(0) - (containsInnerLines ? minX : trailingRanges.get(0));
        int adjustY = leftGap == 0 ? 2 : leftGap > 0 ? 1 : 0; // avoiding 1-pixel gap between aligned lines
        for (int i = 0; i < leadingRanges.size() - 1; i += 2) {
          start = leadingRanges.get(i);
          end = leadingRanges.get(i + 1);
          if (i > 0) {
            drawLine(g, leadingRanges.get(i - 1), leadingBottomY, start, leadingBottomY, rounded);
          }
          drawLine(g, start, leadingBottomY + (i == 0 ? adjustY : 0), start, leadingTopY, rounded);
          if ((i + 2) < leadingRanges.size()) {
            drawLine(g, start, leadingTopY, end, leadingTopY, rounded);
            drawLine(g, end, leadingTopY, end, leadingBottomY, rounded);
          }
        }
        end = Math.max(end, maxX);
        drawLine(g, start, leadingTopY, end, leadingTopY, rounded);
        drawLine(g, end, leadingTopY, end, trailingTopY - 1, rounded);
        float targetX = trailingRanges.get(trailingRanges.size() - 1);
        drawLine(g, end, trailingTopY - 1, targetX, trailingTopY - 1, rounded);
        adjustY = end == targetX ? -2 : -1; // for lastX == targetX we need to avoid a gap when rounding is used
        for (int i = trailingRanges.size() - 2; i >= 0; i -= 2) {
          start = trailingRanges.get(i);
          end = trailingRanges.get(i + 1);

          drawLine(g, end, trailingTopY + (i == 0 ? adjustY : 0), end, trailingBottomY, rounded);
          drawLine(g, end, trailingBottomY, start, trailingBottomY, rounded);
          drawLine(g, start, trailingBottomY, start, trailingTopY, rounded);
          if (i > 0) {
            drawLine(g, start, trailingTopY, trailingRanges.get(i - 1), trailingTopY, rounded);
          }
        }
        float lastX = start;
        if (containsInnerLines) {
          if (start != minX) {
            drawLine(g, start, trailingTopY, start, trailingTopY - 1, rounded);
            drawLine(g, start, trailingTopY - 1, minX, trailingTopY - 1, rounded);
            drawLine(g, minX, trailingTopY - 1, minX, leadingBottomY + 1, rounded);
          }
          else {
            drawLine(g, minX, trailingTopY, minX, leadingBottomY + 1, rounded);
          }
          lastX = minX;
        }
        targetX = leadingRanges.get(0);
        if (lastX < targetX) {
          drawLine(g, lastX, leadingBottomY + 1, targetX, leadingBottomY + 1, rounded);
        }
        else {
          drawLine(g, lastX, leadingBottomY + 1, lastX, leadingBottomY, rounded);
          drawLine(g, lastX, leadingBottomY, targetX, leadingBottomY, rounded);
        }
      }
    }
  }

  private void drawSimpleBorder(Graphics2D g, float xStart, float xEnd, float y, boolean rounded) {
    Shape border = getBorderShape(xStart, y, xEnd - xStart, myView.getLineHeight(), 1, rounded);
    if (border != null) {
      Object old = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
      g.fill(border);
      g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, old);
    }
  }

  private static Shape getBorderShape(float x, float y, float width, int height, int thickness, boolean rounded) {
    if (width <= 0 || height <= 0) return null;
    Shape outer = rounded
                  ? new RoundRectangle2D.Float(x, y, width, height, 2, 2)
                  : new Rectangle2D.Float(x, y, width, height);
    int doubleThickness = 2 * thickness;
    if (width <= doubleThickness || height <= doubleThickness) return outer;
    Shape inner = new Rectangle2D.Float(x + thickness, y + thickness, width - doubleThickness, height - doubleThickness);

    Path2D path = new Path2D.Float(Path2D.WIND_EVEN_ODD);
    path.append(outer, false);
    path.append(inner, false);
    return path;
  }

  private static void drawLine(Graphics2D g, float x1, int y1, float x2, int y2, boolean rounded) {
    if (rounded) {
      UIUtil.drawLinePickedOut(g, (int) x1, y1, (int)x2, y2);
    } else {
      LinePainter2D.paint(g, (int)x1, y1, (int)x2, y2);
    }
  }

  /**
   * Returns ranges obtained from {@link #logicalRangeToVisualRanges(int, int)}, adjusted for painting range border - lines should
   * line inside target ranges (except for empty range). Target offsets are supposed to be located on the same visual line.
   */
  private TFloatArrayList adjustedLogicalRangeToVisualRanges(int startOffset, int endOffset) {
    TFloatArrayList ranges = logicalRangeToVisualRanges(startOffset, endOffset);
    for (int i = 0; i < ranges.size() - 1; i += 2) {
      float startX = ranges.get(i);
      float endX = ranges.get(i + 1);
      if (startX == endX) {
        if (startX > 0) {
          startX--;
        }
        else {
          endX++;
        }
      }
      else {
        endX--;
      }
      ranges.set(i, startX);
      ranges.set(i + 1, endX);
    }
    return ranges;
  }


    /**
     * Returns a list of pairs of x coordinates for visual ranges representing given logical range. If
     * {@code startOffset == endOffset}, a pair of equal numbers is returned, corresponding to target position. Target offsets are
     * supposed to be located on the same visual line.
     */
  private TFloatArrayList logicalRangeToVisualRanges(int startOffset, int endOffset) {
    assert startOffset <= endOffset;
    TFloatArrayList result = new TFloatArrayList();
    if (myDocument.getTextLength() == 0) {
      int minX = myCorrector.emptyTextX();
      result.add(minX);
      result.add(minX);
    }
    else {
      float lastX = -1;
      for (VisualLineFragmentsIterator.Fragment fragment : VisualLineFragmentsIterator.create(myView, startOffset, false, true)) {
        int minOffset = fragment.getMinOffset();
        int maxOffset = fragment.getMaxOffset();
        if (startOffset == endOffset) {
          lastX = fragment.getEndX();
          Inlay inlay = fragment.getCurrentInlay();
          if (inlay != null && !inlay.isRelatedToPrecedingText()) continue;
          if (startOffset >= minOffset && startOffset < maxOffset) {
            float x = fragment.offsetToX(startOffset);
            result.add(x);
            result.add(x);
            break;
          }
        }
        else if (startOffset < maxOffset && endOffset > minOffset) {
          float x1 = minOffset == maxOffset ? fragment.getStartX() : fragment.offsetToX(Math.max(minOffset, startOffset));
          float x2 = minOffset == maxOffset ? fragment.getEndX() : fragment.offsetToX(Math.min(maxOffset, endOffset));
          if (x1 > x2) {
            float tmp = x1;
            x1 = x2;
            x2 = tmp;
          }
          if (result.isEmpty() || x1 > result.get(result.size() - 1)) {
            result.add(x1);
            result.add(x2);
          }
          else {
            result.set(result.size() - 1, x2);
          }
        }
      }
      if (startOffset == endOffset && result.isEmpty() && lastX >= 0) {
        result.add(lastX);
        result.add(lastX);
      }
    }
    return result;
  }

  private void paintComposedTextDecoration(Graphics2D g, int yShift) {
    TextRange composedTextRange = myEditor.getComposedTextRange();
    if (composedTextRange != null) {
      Point2D p1 = myView.offsetToXY(Math.min(composedTextRange.getStartOffset(), myDocument.getTextLength()), true, false);
      Point2D p2 = myView.offsetToXY(Math.min(composedTextRange.getEndOffset(), myDocument.getTextLength()), false, true);

      int y = (int)p1.getY() + myView.getAscent() + 1 + yShift;

      g.setStroke(IME_COMPOSED_TEXT_UNDERLINE_STROKE);
      g.setColor(myEditor.getColorsScheme().getDefaultForeground());
      LinePainter2D.paint(g, (int)p1.getX(), y, (int)p2.getX(), y);
    }
  }

  private void paintBlockInlays(Graphics2D g,
                                Rectangle clip,
                                int yShift,
                                int startVisualLine,
                                int endVisualLine) {
    if (!myEditor.getInlayModel().hasBlockElements()) return;
    int startX = myView.getInsets().left;
    int lineCount = myEditor.getVisibleLineCount();
    TextAttributes lineEndAttributes = startVisualLine == 0 ? TextAttributes.ERASE_MARKER : null;
    Iterator<Caret> carets = myEditor.getCaretModel().getAllCarets()
      .stream()
      .filter(Caret::hasSelection)
      .sorted(Comparator.comparingInt(Caret::getSelectionStart))
      .iterator();
    PeekableIterator<Caret> caretIterator = new PeekableIteratorWrapper<>(carets);
    VisualLinesIterator visLinesIterator = new VisualLinesIterator(myEditor, startVisualLine);
    while (!visLinesIterator.atEnd()) {
      int visualLine = visLinesIterator.getVisualLine();
      if (visualLine > endVisualLine || visualLine >= lineCount) break;
      int y = visLinesIterator.getY() + yShift;

      int curY = y;
      List<Inlay> inlaysAbove = visLinesIterator.getBlockInlaysAbove();
      if (!inlaysAbove.isEmpty()) {
        if (lineEndAttributes == null) {
          int lineStartOffset = visLinesIterator.getVisualLineStartOffset();
          lineEndAttributes = getBetweenLinesAttributes(visualLine, lineStartOffset, caretIterator);
        }
        for (Inlay inlay : inlaysAbove) {
          int height = inlay.getHeightInPixels();
          int newY = curY - height;
          paintBackground(g, lineEndAttributes.getBackgroundColor(), startX, newY, clip.x + clip.width - startX, height);
          inlay.getRenderer().paint(inlay, g, new Rectangle(startX, newY, inlay.getWidthInPixels(), height), lineEndAttributes);
          curY = newY;
        }
      }
      lineEndAttributes = null;
      curY = y + myEditor.getLineHeight();
      List<Inlay> inlaysBelow = visLinesIterator.getBlockInlaysBelow();
      if (!inlaysBelow.isEmpty()) {
        int lineStartOffset = getNextVisualLineStartOffset(visLinesIterator);
        lineEndAttributes = getBetweenLinesAttributes(visualLine + 1, lineStartOffset, caretIterator);
        for (Inlay inlay : inlaysBelow) {
          int height = inlay.getHeightInPixels();
          paintBackground(g, lineEndAttributes.getBackgroundColor(), startX, curY, clip.x + clip.width - startX, height);
          inlay.getRenderer().paint(inlay, g, new Rectangle(startX, curY, inlay.getWidthInPixels(), height), lineEndAttributes);
          curY += height;
        }
      }
      visLinesIterator.advance();
    }
  }

  private int getNextVisualLineStartOffset(@NotNull VisualLinesIterator iterator) {
    if (iterator.endsWithSoftWrap()) {
      return iterator.getVisualLineEndOffset();
    }
    else {
      int nextLogicalLine = iterator.getEndLogicalLine() + 1;
      return nextLogicalLine < myDocument.getLineCount() ? myDocument.getLineStartOffset(nextLogicalLine) : myDocument.getTextLength();
    }
  }

  @NotNull
  private TextAttributes getBetweenLinesAttributes(int bottomVisualLine,
                                                   int bottomVisualLineStartOffset,
                                                   PeekableIterator<Caret> caretIterator) {
    boolean selection = false;
    while (caretIterator.hasNext() && caretIterator.peek().getSelectionEnd() < bottomVisualLineStartOffset) caretIterator.next();
    if (caretIterator.hasNext()) {
      Caret caret = caretIterator.peek();
      selection = caret.getSelectionStart() <= bottomVisualLineStartOffset &&
                  caret.getSelectionStartPosition().line < bottomVisualLine && bottomVisualLine <= caret.getSelectionEndPosition().line;
    }

    class MyProcessor implements Processor<RangeHighlighterEx> {
      private int layer;
      private Color backgroundColor;

      private MyProcessor(boolean selection) {
        backgroundColor = selection ? myEditor.getSelectionModel().getTextAttributes().getBackgroundColor() : null;
        layer = backgroundColor == null ? Integer.MIN_VALUE : HighlighterLayer.SELECTION;
      }

      @Override
      public boolean process(RangeHighlighterEx highlighterEx) {
        int layer = highlighterEx.getLayer();
        if (layer > this.layer &&
            highlighterEx.getAffectedAreaStartOffset() < bottomVisualLineStartOffset &&
            highlighterEx.getAffectedAreaEndOffset() > bottomVisualLineStartOffset) {
          TextAttributes attributes = highlighterEx.getTextAttributes();
          Color backgroundColor = attributes == null ? null : attributes.getBackgroundColor();
          if (backgroundColor != null) {
            this.layer = layer;
            this.backgroundColor = backgroundColor;
          }
        }
        return true;
      }
    }
    MyProcessor processor = new MyProcessor(selection);
    myEditor.getFilteredDocumentMarkupModel()
      .processRangeHighlightersOverlappingWith(bottomVisualLineStartOffset, bottomVisualLineStartOffset, processor);
    myEditor.getMarkupModel().processRangeHighlightersOverlappingWith(bottomVisualLineStartOffset, bottomVisualLineStartOffset, processor);
    TextAttributes attributes = new TextAttributes();
    attributes.setBackgroundColor(processor.backgroundColor);
    return attributes;
  }

  private void paintCaret(Graphics2D g_, int yShift) {
    EditorImpl.CaretRectangle[] locations = myEditor.getCaretLocations(true);
    if (locations == null) return;

    Graphics2D g = IdeBackgroundUtil.getOriginalGraphics(g_);
    int nominalLineHeight = myView.getNominalLineHeight();
    int topOverhang = myView.getTopOverhang();
    EditorSettings settings = myEditor.getSettings();
    Color caretColor = myEditor.getColorsScheme().getColor(EditorColors.CARET_COLOR);
    if (caretColor == null) caretColor = new JBColor(CARET_DARK, CARET_LIGHT);
    int minX = myView.getInsets().left;
    for (EditorImpl.CaretRectangle location : locations) {
      float x = location.myPoint.x;
      int y = location.myPoint.y - topOverhang + yShift;
      Caret caret = location.myCaret;
      CaretVisualAttributes attr = caret == null ? CaretVisualAttributes.DEFAULT : caret.getVisualAttributes();
      g.setColor(attr.getColor() != null ? attr.getColor() : caretColor);
      boolean isRtl = location.myIsRtl;
      if (myEditor.isInsertMode() != settings.isBlockCursor()) {
        int lineWidth = JBUIScale.scale(attr.getWidth(settings.getLineCursorWidth()));
        // fully cover extra character's pixel which can appear due to antialiasing
        // see IDEA-148843 for more details
        if (x > minX && lineWidth > 1) x -= 1 / JBUIScale.sysScale(g);
        g.fill(new Rectangle2D.Float(x, y, lineWidth, nominalLineHeight));
        if (myDocument.getTextLength() > 0 && caret != null &&
            !myView.getTextLayoutCache().getLineLayout(caret.getLogicalPosition().line).isLtr()) {
          GeneralPath triangle = new GeneralPath(Path2D.WIND_NON_ZERO, 3);
          triangle.moveTo(isRtl ? x + lineWidth : x, y);
          triangle.lineTo(isRtl ? x + lineWidth - CARET_DIRECTION_MARK_SIZE : x + CARET_DIRECTION_MARK_SIZE, y);
          triangle.lineTo(isRtl ? x + lineWidth : x, y + CARET_DIRECTION_MARK_SIZE);
          triangle.closePath();
          g.fill(triangle);
        }
      }
      else {
        int width = location.myWidth;
        float startX = Math.max(minX, isRtl ? x - width : x);
        g.fill(new Rectangle2D.Float(startX, y, width, nominalLineHeight));
        if (myDocument.getTextLength() > 0 && caret != null) {
          int targetVisualColumn = caret.getVisualPosition().column - (isRtl ? 1 : 0);
          for (VisualLineFragmentsIterator.Fragment fragment : VisualLineFragmentsIterator.create(myView,
                                                                                                  caret.getVisualLineStart(),
                                                                                                  false)) {
            if (fragment.getCurrentInlay() != null) continue;
            int startVisualColumn = fragment.getStartVisualColumn();
            int endVisualColumn = fragment.getEndVisualColumn();
            if (startVisualColumn <= targetVisualColumn && targetVisualColumn < endVisualColumn) {
              g.setColor(ColorUtil.isDark(caretColor) ? CARET_LIGHT : CARET_DARK);
              fragment.draw(g, startX, y + topOverhang + myView.getAscent(),
                            fragment.visualColumnToOffset(targetVisualColumn - startVisualColumn),
                            fragment.visualColumnToOffset(targetVisualColumn + 1 - startVisualColumn));
              break;
            }
          }
          ComplexTextFragment.flushDrawingCache(g);
        }
      }
    }
  }

  void repaintCarets() {
    EditorImpl.CaretRectangle[] locations = myEditor.getCaretLocations(false);
    if (locations == null) return;
    int nominalLineHeight = myView.getNominalLineHeight();
    int topOverhang = myView.getTopOverhang();
    for (EditorImpl.CaretRectangle location : locations) {
      int x = location.myPoint.x;
      int y = location.myPoint.y - topOverhang;
      int width = Math.max(location.myWidth, CARET_DIRECTION_MARK_SIZE);
      myEditor.getContentComponent().repaintEditorComponentExact(x - width, y, width * 2, nominalLineHeight);
    }
  }

  private interface MarginWidthConsumer {
    void process(float width);
  }

  private void paintLineFragments(Graphics2D g, Rectangle clip, VisualLinesIterator visLineIterator, IterationState.CaretData caretData,
                                  int y, LineFragmentPainter painter, MarginWidthConsumer marginWidthConsumer) {
    int visualLine = visLineIterator.getVisualLine();
    float x = myCorrector.startX(visualLine) + (visualLine == 0 ? myView.getPrefixTextWidthInPixels() : 0);
    int offset = visLineIterator.getVisualLineStartOffset();
    int visualLineEndOffset = visLineIterator.getVisualLineEndOffset();
    IterationState it = null;
    int prevEndOffset = -1;
    boolean firstFragment = true;
    int maxColumn = 0;
    int marginColumns = myEditor.getSettings().getRightMargin(myEditor.getProject());
    int endLogicalLine = visLineIterator.getEndLogicalLine();
    boolean marginReached = false;
    for (VisualLineFragmentsIterator.Fragment fragment : VisualLineFragmentsIterator.create(myView, visLineIterator, null, true)) {
      int fragmentStartOffset = fragment.getStartOffset();
      int start = fragmentStartOffset;
      int end = fragment.getEndOffset();
      x = fragment.getStartX();
      if (firstFragment) {
        firstFragment = false;
        SoftWrap softWrap = myEditor.getSoftWrapModel().getSoftWrap(offset);
        boolean hasSoftWrap = softWrap != null;
        if (hasSoftWrap || myEditor.isRightAligned()) {
          prevEndOffset = offset;
          it = new IterationState(myEditor, offset == 0 ? 0 : DocumentUtil.getPreviousCodePointOffset(myDocument, offset), visualLineEndOffset,
                                  caretData, false, false, false, false);
          if (it.getEndOffset() <= offset) {
            it.advance();
          }
          if (x >= clip.getMinX()) {
            TextAttributes attributes = it.getStartOffset() == offset ? it.getBeforeLineStartBackgroundAttributes() :
                                        it.getMergedAttributes();
            painter.paintBeforeLineStart(g, attributes, hasSoftWrap, fragment.getStartVisualColumn(), x, y);
          }
        }
      }
      FoldRegion foldRegion = fragment.getCurrentFoldRegion();
      if (foldRegion == null) {
        if (start != prevEndOffset) {
          it = new IterationState(myEditor, start, fragment.isRtl() ? offset : visualLineEndOffset,
                                  caretData, false, false, false, fragment.isRtl());
        }
        prevEndOffset = end;
        assert it != null;
        if (start == end) { // special case of inlays
          if (start == it.getEndOffset() && !it.atEnd()) {
            it.advance();
          }
          TextAttributes attributes = it.getStartOffset() == start ? it.getBreakAttributes() : it.getMergedAttributes();
          float xNew = fragment.getEndX();
          if (xNew >= clip.getMinX()) {
            painter.paint(g, fragment, 0, 0, attributes, x, xNew, y);
          }
          x = xNew;
        }
        else {
          while (fragment.isRtl() ? start > end : start < end) {
            if (fragment.isRtl() ? it.getEndOffset() >= start : it.getEndOffset() <= start) {
              assert !it.atEnd();
              it.advance();
            }
            TextAttributes attributes = it.getMergedAttributes();
            int curEnd = fragment.isRtl() ? Math.max(it.getEndOffset(), end) : Math.min(it.getEndOffset(), end);
            float xNew = fragment.offsetToX(x, start, curEnd);
            if (xNew >= clip.getMinX()) {
              painter.paint(g, fragment,
                            fragment.isRtl() ? fragmentStartOffset - start : start - fragmentStartOffset,
                            fragment.isRtl() ? fragmentStartOffset - curEnd : curEnd - fragmentStartOffset,
                            attributes, x, xNew, y);
            }
            x = xNew;
            start = curEnd;
          }
          if (marginWidthConsumer != null && fragment.getEndLogicalLine() == endLogicalLine &&
              fragment.getStartLogicalColumn() <= marginColumns && fragment.getEndLogicalColumn() > marginColumns) {
            marginWidthConsumer.process(fragment.visualColumnToX(fragment.logicalToVisualColumn(marginColumns)));
            marginReached = true;
          }
        }
      }
      else {
        float xNew = fragment.getEndX();
        if (xNew >= clip.getMinX()) {
          painter.paint(g, fragment, 0, fragment.getVisualLength(), getFoldRegionAttributes(foldRegion), x, xNew, y);
        }
        x = xNew;
        prevEndOffset = -1;
        it = null;
      }
      if (x > clip.getMaxX()) return;
      maxColumn = fragment.getEndVisualColumn();
    }
    if (firstFragment && myEditor.isRightAligned()) {
      it = new IterationState(myEditor, offset, visualLineEndOffset, caretData, false, false, false, false);
      if (it.getEndOffset() <= offset) {
        it.advance();
      }
      painter.paintBeforeLineStart(g, it.getBeforeLineStartBackgroundAttributes(), false, maxColumn, x, y);
    }
    if (it == null || it.getEndOffset() != visualLineEndOffset) {
      it = new IterationState(myEditor, visualLineEndOffset == offset
                                        ? visualLineEndOffset : DocumentUtil.getPreviousCodePointOffset(myDocument, visualLineEndOffset),
                              visualLineEndOffset, caretData, false, false, false, false);
    }
    if (!it.atEnd()) {
      it.advance();
    }
    assert it.atEnd();
    painter.paintAfterLineEnd(g, clip, it, maxColumn, x, y);
    if (marginWidthConsumer != null && !marginReached &&
        (visualLine == myEditor.getCaretModel().getVisualPosition().line || x > marginColumns * myView.getPlainSpaceWidth())) {
      int endLogicalColumn = myView.offsetToLogicalPosition(visualLineEndOffset).column;
      if (endLogicalColumn <= marginColumns) {
        marginWidthConsumer.process(x + (marginColumns - endLogicalColumn) * myView.getPlainSpaceWidth());
      }
    }
  }

  private TextAttributes getFoldRegionAttributes(FoldRegion foldRegion) {
    TextAttributes selectionAttributes = isSelected(foldRegion) ? myEditor.getSelectionModel().getTextAttributes() : null;
    TextAttributes defaultAttributes = getDefaultAttributes();
    if (myEditor.isInFocusMode(foldRegion)) {
      return ObjectUtils.notNull(myEditor.getUserData(FocusModeModel.FOCUS_MODE_ATTRIBUTES), getDefaultAttributes());
    }
    TextAttributes foldAttributes = myEditor.getFoldingModel().getPlaceholderAttributes();
    return mergeAttributes(mergeAttributes(selectionAttributes, foldAttributes), defaultAttributes);
  }

  @SuppressWarnings("UseJBColor")
  private TextAttributes getDefaultAttributes() {
    TextAttributes attributes = myEditor.getColorsScheme().getAttributes(HighlighterColors.TEXT);
    if (attributes.getForegroundColor() == null) attributes.setForegroundColor(Color.black);
    if (attributes.getBackgroundColor() == null) attributes.setBackgroundColor(Color.white);
    return attributes;
  }

  private static boolean isSelected(FoldRegion foldRegion) {
    int regionStart = foldRegion.getStartOffset();
    int regionEnd = foldRegion.getEndOffset();
    int[] selectionStarts = foldRegion.getEditor().getSelectionModel().getBlockSelectionStarts();
    int[] selectionEnds = foldRegion.getEditor().getSelectionModel().getBlockSelectionEnds();
    for (int i = 0; i < selectionStarts.length; i++) {
      int start = selectionStarts[i];
      int end = selectionEnds[i];
      if (regionStart >= start && regionEnd <= end) return true;
    }
    return false;
  }

  private static TextAttributes mergeAttributes(TextAttributes primary, TextAttributes secondary) {
    if (primary == null) return secondary;
    if (secondary == null) return primary;
    TextAttributes result =
      new TextAttributes(primary.getForegroundColor() == null ? secondary.getForegroundColor() : primary.getForegroundColor(),
                         primary.getBackgroundColor() == null ? secondary.getBackgroundColor() : primary.getBackgroundColor(),
                         null, null,
                         primary.getFontType() == Font.PLAIN ? secondary.getFontType() : primary.getFontType());

    return TextAttributesEffectsBuilder.create(secondary).coverWith(primary).applyTo(result);
  }

  @Override
  public void drawChars(@NotNull Graphics g, @NotNull char[] data, int start, int end, int x, int y, Color color, FontInfo fontInfo) {
    g.setFont(fontInfo.getFont());
    g.setColor(color);
    g.drawChars(data, start, end - start, x, y);
  }

  interface LineFragmentPainter {
    void paintBeforeLineStart(Graphics2D g, TextAttributes attributes, boolean hasSoftWrap, int columnEnd, float xEnd, int y);
    void paint(Graphics2D g, VisualLineFragmentsIterator.Fragment fragment, int start, int end, TextAttributes attributes,
               float xStart, float xEnd, int y);
    void paintAfterLineEnd(Graphics2D g, Rectangle clip, IterationState iterationState, int columnStart, float x, int y);
  }

  private static class LineWhitespacePaintingStrategy {
    private final boolean myWhitespaceShown;
    private final boolean myLeadingWhitespaceShown;
    private final boolean myInnerWhitespaceShown;
    private final boolean myTrailingWhitespaceShown;

    // Offsets on current line where leading whitespace ends and trailing whitespace starts correspondingly.
    private int currentLeadingEdge;
    private int currentTrailingEdge;

    LineWhitespacePaintingStrategy(EditorSettings settings) {
      myWhitespaceShown = settings.isWhitespacesShown();
      myLeadingWhitespaceShown = settings.isLeadingWhitespaceShown();
      myInnerWhitespaceShown = settings.isInnerWhitespaceShown();
      myTrailingWhitespaceShown = settings.isTrailingWhitespaceShown();
    }

    private boolean showAnyWhitespace() {
      return myWhitespaceShown && (myLeadingWhitespaceShown || myInnerWhitespaceShown || myTrailingWhitespaceShown);
    }

    private void update(CharSequence chars, int lineStart, int lineEnd) {
      if (showAnyWhitespace()) {
        currentTrailingEdge = CharArrayUtil.shiftBackward(chars, lineStart, lineEnd - 1, WHITESPACE_CHARS) + 1;
        currentLeadingEdge = CharArrayUtil.shiftForward(chars, lineStart, currentTrailingEdge, WHITESPACE_CHARS);
      }
    }

    private boolean showWhitespaceAtOffset(int offset) {
      return myWhitespaceShown
             && (offset < currentLeadingEdge ? myLeadingWhitespaceShown :
                 offset >= currentTrailingEdge ? myTrailingWhitespaceShown :
                 myInnerWhitespaceShown);
    }

    private boolean isAdvancedHighlighting(int offset) {
      return offset < currentLeadingEdge;
    }
  }

  private interface XCorrector {
    float startX(int line);
    int lineWidth(int line, float x);
    int emptyTextX();
    int minX(int startLine, int endLine);
    int maxX(int startLine, int endLine);
    int lineSeparatorStart(int minX);
    int lineSeparatorEnd(int maxX);
    float singleLineBorderStart(float x);
    float singleLineBorderEnd(float x);
    int marginX(float marginWidth);
    List<Integer> softMarginsX();

    @NotNull
    default XCorrector align(@NotNull EditorView view) {
      boolean rightAligned = view.getEditor().isRightAligned();
      return rightAligned && this instanceof RightAligned || !rightAligned && this instanceof LeftAligned ? this : create(view);
    }

    @NotNull
    static XCorrector create(@NotNull EditorView view) {
      return view.getEditor().isRightAligned() ? new RightAligned(view) : new LeftAligned(view);
    }

    class LeftAligned implements XCorrector {
      private final EditorView myView;

      private LeftAligned(@NotNull EditorView view) {
        myView = view;
      }

      @Override
      public float startX(int line) {
        return myView.getInsets().left;
      }

      @Override
      public int emptyTextX() {
        return myView.getInsets().left;
      }

      @Override
      public int minX(int startLine, int endLine) {
        return myView.getInsets().left;
      }

      @Override
      public int maxX(int startLine, int endLine) {
        return minX(startLine, endLine) + myView.getMaxTextWidthInLineRange(startLine, endLine - 1) - 1;
      }

      @Override
      public float singleLineBorderStart(float x) {
        return x;
      }

      @Override
      public float singleLineBorderEnd(float x) {
        return x + 1;
      }

      @Override
      public int lineWidth(int line, float x) {
        return (int)x - myView.getInsets().left;
      }

      @Override
      public int lineSeparatorStart(int maxX) {
        return myView.getInsets().left;
      }

      @Override
      public int lineSeparatorEnd(int maxX) {
        return isMarginShown(myView.getEditor()) ? Math.min(marginX(getBaseMarginWidth(myView)), maxX) : maxX;
      }

      @Override
      public int marginX(float marginWidth) {
        return (int)(myView.getInsets().left + marginWidth);
      }

      @Override
      public List<Integer> softMarginsX() {
        List<Integer> margins = myView.getEditor().getSettings().getSoftMargins();
        List<Integer> result = new ArrayList<>(margins.size());
        for (Integer margin : margins) {
          result.add((int)(myView.getInsets().left + margin * myView.getPlainSpaceWidth()));
        }
        return result;
      }
    }

    class RightAligned implements XCorrector {
      private final EditorView myView;

      private RightAligned(@NotNull EditorView view) {
        myView = view;
      }

      @Override
      public float startX(int line) {
        return myView.getRightAlignmentLineStartX(line);
      }

      @Override
      public int lineWidth(int line, float x) {
        return (int)(x - myView.getRightAlignmentLineStartX(line));
      }

      @Override
      public int emptyTextX() {
        return myView.getRightAlignmentMarginX();
      }

      @Override
      public int minX(int startLine, int endLine) {
        return myView.getRightAlignmentMarginX() - myView.getMaxTextWidthInLineRange(startLine, endLine - 1) - 1;
      }

      @Override
      public int maxX(int startLine, int endLine) {
        return myView.getRightAlignmentMarginX() - 1;
      }

      @Override
      public float singleLineBorderStart(float x) {
        return x - 1;
      }

      @Override
      public float singleLineBorderEnd(float x) {
        return x;
      }

      @Override
      public int lineSeparatorStart(int minX) {
        return isMarginShown(myView.getEditor()) ? Math.max(marginX(getBaseMarginWidth(myView)), minX) : minX;
      }

      @Override
      public int lineSeparatorEnd(int maxX) {
        return maxX;
      }

      @Override
      public int marginX(float marginWidth) {
        return (int)(myView.getRightAlignmentMarginX() - marginWidth);
      }

      @Override
      public List<Integer> softMarginsX() {
        List<Integer> margins = myView.getEditor().getSettings().getSoftMargins();
        List<Integer> result = new ArrayList<>(margins.size());
        for (Integer margin : margins) {
          result.add((int)(myView.getRightAlignmentMarginX() - margin * myView.getPlainSpaceWidth()));
        }
        return result;
      }
    }
  }

  private static class LineExtensionData {
    private final LineExtensionInfo info;
    private final LineLayout layout;

    private LineExtensionData(LineExtensionInfo info, LineLayout layout) {
      this.info = info;
      this.layout = layout;
    }
  }

  private static class MarginPositions {
    private final float[] x;
    private final int[] y;

    private MarginPositions(int size) {
      x = new float[size];
      y = new int[size];
    }
  }
}
