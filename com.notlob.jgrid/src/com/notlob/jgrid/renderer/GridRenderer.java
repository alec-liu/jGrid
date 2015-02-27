package com.notlob.jgrid.renderer;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.PaintEvent;
import org.eclipse.swt.events.PaintListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.Font;
import org.eclipse.swt.graphics.FontData;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Image;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.graphics.Rectangle;
import org.eclipse.swt.graphics.TextLayout;

import com.notlob.jgrid.Grid;
import com.notlob.jgrid.model.Column;
import com.notlob.jgrid.model.GridModel;
import com.notlob.jgrid.model.Row;
import com.notlob.jgrid.model.SortDirection;
import com.notlob.jgrid.model.Viewport;
import com.notlob.jgrid.model.filtering.Filter;
import com.notlob.jgrid.model.filtering.QuickFilter;
import com.notlob.jgrid.styles.AlignmentStyle;
import com.notlob.jgrid.styles.BorderStyle;
import com.notlob.jgrid.styles.CellStyle;
import com.notlob.jgrid.styles.ContentStyle;
import com.notlob.jgrid.styles.StyleRegistry;
import com.notlob.jgrid.util.ResourceManager;

public class GridRenderer<T> implements PaintListener {

	protected final Grid<T> grid;
	protected final Viewport<T> viewport;

	// These are used during a render pass. It saves creating lots of them on the fly - instead we'll recycle these instances.
	protected final Point rowLocation;
	protected final Point content;
	protected final Rectangle rowBounds;
	protected final Rectangle cellBounds;
	protected final Rectangle innerBounds;
	protected final Rectangle selectionRegion;
	protected final Point topLeft;
	protected final Point topRight;
	protected final Point bottomLeft;
	protected final Point bottomRight;
	protected final Point fieldLocation;
	protected final Point groupBottomLeft;
	protected final Point groupBottomRight;
	protected final TextLayout textLayout;

	// The current row index being painted (used for painting row numbers).
	protected int rowIndex;

	protected final static RGB RGB__SHADOW_DARK = new RGB(80, 80, 80);
	protected final static RGB RGB__SHADOW_HIGHLIGHT = new RGB(245, 245, 245);

	protected enum RenderPass {
		BACKGROUND,
		FOREGROUND
	}

	// By painting in two passes we get to avoid issues with background fills leaving gaps where a cell has no border
	// or if there is a border, we avoid issues where the next cell's background overites it (with no spacing).
	protected RenderPass renderPass;

	// Are we painting an alternate background row?
	protected boolean alternate;

	protected final Map<String, Point> extentCache;
	
	protected boolean errorLogged;
	protected Image errorImage;

	// Double-buffering image. Used as a key for the setData method.
	private final static String DATA__DOUBLE_BUFFER_IMAGE = "double-buffer-image"; //$NON-NLS-1$
	
	public GridRenderer(final Grid<T> grid) {
		this.grid = grid;
		viewport = grid.getViewport();
		content = new Point(0, 0);
		rowLocation = new Point(0, 0);
		rowBounds = new Rectangle(0, 0, 0, 0);
		cellBounds = new Rectangle(0, 0, 0, 0);
		topLeft = new Point(0, 0);
		topRight = new Point(0, 0);
		bottomLeft = new Point(0, 0);
		bottomRight = new Point(0, 0);
		fieldLocation = new Point(0, 0);
		innerBounds = new Rectangle(0, 0, 0, 0);
		extentCache = new HashMap<>();
		selectionRegion = new Rectangle(0, 0, 0, 0);
		groupBottomLeft = new Point(0, 0);
		groupBottomRight = new Point(0, 0);
		textLayout = new TextLayout(grid.getDisplay());
		errorImage = ResourceManager.getInstance().getImage("cell_error.gif");
	}

	@Override
	public void paintControl(final PaintEvent e) {
		GC gc = null;
		try {
			//
			// Double-buffer the paint event.
			//
			Image image = (Image) grid.getData(DATA__DOUBLE_BUFFER_IMAGE);
		    if ((image == null) || (image.getBounds().width != grid.getSize().x) || (image.getBounds().height != grid.getSize().y)) {
		    	//
		    	// If the old image no longer fits the bounds, trash it.
		    	//
		    	if (image != null) {
		    		image.dispose();
		    	}

		    	//
		    	// Store the double-buffer image in the data of the canvas.
		    	//
		    	image = new Image(grid.getDisplay(), grid.getSize().x, grid.getSize().y);
		    	grid.setData(DATA__DOUBLE_BUFFER_IMAGE, image);
		    }

		    gc = new GC(image);
			gc.setBackground(getColour(getStyleRegistry().getBackgroundColour()));
			gc.fillRectangle(grid.getClientArea());
			gc.setAntialias(SWT.ON);
			gc.setTextAntialias(SWT.ON);

			if (getGridModel() != null && !getGridModel().getColumns().isEmpty()) {
				//
				// Calculate the viewport ranges.
				//
				viewport.calculateVisibleCellRange(gc);

				//
				// Paint the grid and cell backgrounds.
				//
				renderPass = RenderPass.BACKGROUND;
				paintRows(gc);
				paintSelection(gc);

				//
				// Paint the grid and cell foregrounds.
				//
				renderPass = RenderPass.FOREGROUND;
				paintRows(gc);
				paintSelection(gc);

			} else {
				//
				// Paint the 'no data' message.
				//
				final String text = grid.getEmptyMessage() == null ? "No data" : grid.getEmptyMessage();
				if (!extentCache.containsKey(text)) {
					extentCache.put(text, gc.textExtent(text));
				}

				final CellStyle cellStyle = getStyleRegistry().getNoDataStyle();
				final Point point = extentCache.get(grid.getEmptyMessage());
				
				if (point != null) {
					align(point.x, point.y, grid.getClientArea(), cellStyle.getTextAlignment(), cellStyle);
					gc.drawText(text, content.x, content.y, SWT.DRAW_TRANSPARENT);
				}
			}

			//
			// Paint the image to the real GC now.
			//
			e.gc.drawImage(image, 0, 0);

		} catch (final Exception ex) {
			ex.printStackTrace();
			
		} finally {
			if (gc != null) {
				gc.dispose();
			}
		}
	}

	/**
	 * Paint the selection region's background OR the selection region's borders.
	 */
	protected void paintSelection(final GC gc) {
		final GridModel<T> gridModel = getGridModel();
		final Rectangle viewportArea = viewport.getViewportArea(gc);
		final boolean paintLeftEdge = (viewport.getFirstColumnIndex() == 0);
		final boolean paintRightEdge = viewport.getVisibleRowWidth(gc) < viewportArea.width;
		boolean paintTopEdge = false;
		boolean paintBottomEdge = false;
		boolean inSelection = false;

		rowLocation.x = viewportArea.x;
		rowLocation.y = viewportArea.y;
		selectionRegion.x = -1;
		selectionRegion.y = -1;
		selectionRegion.width = viewport.getVisibleRowWidth(gc);
		selectionRegion.height= -1;

		//
		// Paint selected row regions - a region is a contiguous block of selected rows.
		//
		for (int rowIndex=viewport.getFirstRowIndex(); rowIndex<viewport.getLastRowIndex(); rowIndex++) {
			final Row<T> row = gridModel.getRows().get(rowIndex);

			if (row.isSelected()) {
				if (inSelection) {
					//
					// Expand the selction region.
					//
					selectionRegion.height += gridModel.getRowHeight(gc, row);

				} else {
					//
					// Start a new selection region.
					//
					selectionRegion.x = rowLocation.x;
					selectionRegion.y = rowLocation.y;
					selectionRegion.height = gridModel.getRowHeight(gc, row);

					//
					// If the previous row is selected then do paint a top edge, as the selection region
					// begins above the viewport.
					//
					paintTopEdge = !((rowIndex > 0) && gridModel.getRows().get(rowIndex - 1).isSelected());
				}
			} else if (inSelection) {
				//
				// This is the next row after a selection region. We now need to paint the region.
				//
				paintSelectionRegion(gc, selectionRegion, paintTopEdge, paintRightEdge, true, paintLeftEdge);
			}

			inSelection = row.isSelected();
			rowLocation.y += (gridModel.getRowHeight(gc, row) + getStyleRegistry().getCellSpacingVertical());
		}

		//
		// We'll need to paint the last selection region.
		//
		if (inSelection) {
			//
			// If the next row beyond the viewport exists and is selected, don't draw the bottom
			//
			paintBottomEdge = !(((viewport.getLastRowIndex() + 1) < gridModel.getRows().size()) && (gridModel.getRows().get(viewport.getLastRowIndex() + 1).isSelected()));
			paintSelectionRegion(gc, selectionRegion, paintTopEdge, paintRightEdge, paintBottomEdge, paintLeftEdge);
		}
	}

	/**
	 * Paint foreground or background details for the region of selected rows.
	 */
	protected void paintSelectionRegion(final GC gc, final Rectangle bounds, final boolean paintTop, final boolean paintRight, final boolean paintBottom, final boolean paintLeft) {
		final StyleRegistry<T> styleRegistry = getStyleRegistry();

		if (renderPass == RenderPass.BACKGROUND) {
			gc.setAlpha(styleRegistry.getSelectionBackgroundOpacity());

			if (styleRegistry.getSelectionBackgroundGradient1() == null || styleRegistry.getSelectionBackgroundGradient2() == null) {
				//
				// Fill with no Gradient
				//
				gc.setBackground(getColour(styleRegistry.getSelectionBackground()));
				gc.fillRectangle(bounds);

			} else {
				//
				// Fill with Gradient (upper, lower).
				//
				gc.setForeground(getColour(styleRegistry.getSelectionBackgroundGradient1()));
				gc.setBackground(getColour(styleRegistry.getSelectionBackgroundGradient2()));
				gc.fillGradientRectangle(bounds.x, bounds.y, bounds.width, bounds.height, true);
			}
		}

		if (renderPass == RenderPass.FOREGROUND) {
			//
			// Paint a region border.
			//
			gc.setAlpha(styleRegistry.getSelectionForegroundOpacity());
			gc.setForeground(getColour(styleRegistry.getSelectionBorder().getColour()));
			gc.setLineWidth(styleRegistry.getSelectionBorder().getWidth());

			// Get the bounds corners, but correct for thicker line widths.
			getTopLeft(bounds);
			getTopRight(bounds);
			getBottomRight(bounds);
			getBottomLeft(bounds);
			topLeft.x += (gc.getLineWidth() - 1);
			bottomLeft.x += (gc.getLineWidth() - 1);

			if (paintTop) {
				paintBorderLine(gc, styleRegistry.getSelectionBorder(), topLeft, topRight);
			}

			if (paintRight) {
				paintBorderLine(gc, styleRegistry.getSelectionBorder(), topRight, bottomRight);
			}

			if (paintBottom) {
				paintBorderLine(gc, styleRegistry.getSelectionBorder(), bottomRight, bottomLeft);
			}

			if (paintLeft) {
				paintBorderLine(gc, styleRegistry.getSelectionBorder(), bottomLeft, topLeft);
			}
		}

	}

	/**
	 * Iterate over header then body rows and render.
	 */
	protected void paintRows(final GC gc) {
		final GridModel<T> gridModel = getGridModel();
		final Rectangle viewportArea = viewport.getViewportArea(gc);

		//
		// Paint the corner cell.
		//
		if (getGridModel().isShowRowNumbers()) {
			cellBounds.x = 0;
			cellBounds.y = 0;
			cellBounds.height = viewportArea.y;
			cellBounds.width = viewportArea.x;

			if (renderPass == RenderPass.BACKGROUND) {
				paintCellBackground(gc, cellBounds, getStyleRegistry().getCornerStyle());

			} else if (renderPass == RenderPass.FOREGROUND) {
				paintCellBorders(gc, cellBounds, getStyleRegistry().getCornerStyle());
			}
		}

		//
		// Paint column header row(s).
		//
		rowLocation.x = viewportArea.x;
		rowLocation.y = 0;
		alternate = false;
		for (final Row<T> row : gridModel.getColumnHeaderRows()) {
			paintRow(gc, rowLocation, row);
			rowLocation.y += (gridModel.getRowHeight(gc, row) + getStyleRegistry().getCellSpacingVertical());
		}

		//
		// Paint data rows and row numbers.
		//
		alternate = false;
		rowLocation.x = viewportArea.x;
		rowLocation.y = viewportArea.y;

		for (int rowIndex=viewport.getFirstRowIndex(); rowIndex<viewport.getLastRowIndex(); rowIndex++) {
			final Row<T> row = gridModel.getRows().get(rowIndex);

			if (gridModel.isShowRowNumbers()) {
				paintRowNumber(gc, rowLocation, row, rowIndex);
			}

			if (gridModel.isParentRow(row)) {
				paintGroupRow(gc, rowLocation, row);
			} else {
				paintRow(gc, rowLocation, row);
			}

			rowLocation.y += (gridModel.getRowHeight(gc, row) + getStyleRegistry().getCellSpacingVertical());

			//
			// If there's a next row, and it's in the same group, don't flip the alternate background.
			//
			final int nextIndex = rowIndex + 1;
			if (!((nextIndex < viewport.getLastRowIndex()) && (nextIndex < gridModel.getRows().size()) && (gridModel.isSameGroup(row, gridModel.getRows().get(nextIndex))))) {
				alternate = !alternate;
			}
		}
	}

	protected void paintRowNumber(final GC gc, final Point point, final Row<T> row, final int rowIndex) {
		this.rowIndex = rowIndex;
		cellBounds.x = 0;
		cellBounds.y = point.y;
		cellBounds.height = getGridModel().getRowHeight(gc, row);
		cellBounds.width = point.x;
		paintCell(gc, cellBounds, null, null, row.isSelected() ? getStyleRegistry().getSelectionRowNumberStyle() :  getStyleRegistry().getRowNumberStyle());
	}

	protected void paintGroupRow(final GC gc, final Point point, final Row<T> row) {
		final CellStyle groupValueStyle = grid.getStyleRegistry().getGroupValueStyle();

		rowBounds.x = point.x + 1; // Shift 1 to avoid blatting the row number border line.
		rowBounds.y = point.y;
		rowBounds.width = (viewport.getViewportArea(gc).width);
		rowBounds.height = getGridModel().getRowHeight(gc, row);

		//
		// Paint the row background.
		//
		if ((row.getElement() != null) && (renderPass == RenderPass.BACKGROUND)) {
			gc.setBackground(getColour(alternate ? groupValueStyle.getBackgroundAlternate() : groupValueStyle.getBackground()));
			gc.fillRectangle(rowBounds);
		}
		
		//
		// Paint any footer border.
		//
		if ((row.getElement() != null) && (renderPass == RenderPass.BACKGROUND) && (getStyleRegistry().getGroupFooterBorder() != null)) {
			groupBottomLeft.x = rowBounds.x;
			groupBottomLeft.y = rowBounds.y + rowBounds.height - 1;
			groupBottomRight.x = rowBounds.x + rowBounds.width; 
			groupBottomRight.y = groupBottomLeft.y; 			
			paintBorderLine(gc, getStyleRegistry().getGroupFooterBorder(), groupBottomLeft, groupBottomRight);
		}

		//
		// Paint the grouped values.
		//
		if ((row.getElement() != null) && (renderPass == RenderPass.FOREGROUND)) {
			final CellStyle groupNameStyle = grid.getStyleRegistry().getGroupNameStyle();
			fieldLocation.x = point.x + groupValueStyle.getPaddingLeft();
			fieldLocation.y = point.y + groupValueStyle.getPaddingTop();

			for (final Column column : getGridModel().getGroupByColumns()) {
				final String name = column.getCaption();
				final String value = grid.getLabelProvider().getText(column, row.getElement());
				final String formattedValue = value == null || value.isEmpty() ? "(blank)" : value;

				//
				// Field Name.
				//
				gc.setForeground(getColour(groupNameStyle.getForeground()));
				gc.setFont(getFont(groupNameStyle.getFontData()));
				gc.drawText(name, fieldLocation.x, fieldLocation.y, true);
				fieldLocation.x += (gc.textExtent(name).x + 8);

				//
				// Field Value.
				//
				gc.setForeground(getColour(groupValueStyle.getForeground()));
				gc.setFont(getFont(groupValueStyle.getFontData()));
				gc.drawText(formattedValue, fieldLocation.x, fieldLocation.y, true);
				fieldLocation.x += (gc.textExtent(formattedValue).x + 16);
			}
		}
	}

	protected void paintRow(final GC gc, final Point point, final Row<T> row) {
		final GridModel<T> gridModel = getGridModel();

		rowBounds.x = point.x + 1; // Shift 1 to avoid blatting the row number border line.
		rowBounds.y = point.y + 1;
		rowBounds.width = (viewport.getViewportArea(gc).width);
		rowBounds.height = getGridModel().getRowHeight(gc, row);

		cellBounds.x = point.x;
		cellBounds.y = point.y;
		cellBounds.height = gridModel.getRowHeight(gc, row);

		//
		// Fill the row background (not the header row though).
		//
		if ((row.getElement() != null) && (renderPass == RenderPass.BACKGROUND)) {
			final CellStyle rowStyle = grid.getStyleRegistry().getCellStyle(null, row, grid);
			gc.setBackground(getColour(alternate ? rowStyle.getBackgroundAlternate() : rowStyle.getBackground()));
			gc.fillRectangle(rowBounds);
		}

		//
		// Now paint every cell in the row.
		//
		for (int columnIndex=viewport.getFirstColumnIndex(); columnIndex<viewport.getLastColumnIndex(); columnIndex++) {
			final Column column = gridModel.getColumns().get(columnIndex);
			final CellStyle cellStyle = gridModel.getStyleRegistry().getCellStyle(column, row, grid);

			cellBounds.width = column.getWidth();
			paintCell(gc, cellBounds, column, row, cellStyle);
			cellBounds.x += (cellBounds.width + getStyleRegistry().getCellSpacingHorizontal());
		}
	}

	protected void paintCell(final GC gc, final Rectangle bounds, final Column column, final Row<T> row, final CellStyle cellStyle) {
		try {
			//
			// Paint the cell background.
			//
			if (renderPass == RenderPass.BACKGROUND) {
				paintCellBackground(gc, bounds, cellStyle);
			}
	
			//
			// Paint cell content.
			//
			if (renderPass == RenderPass.FOREGROUND) {
				paintCellContent(gc, bounds, column, row, cellStyle);
				paintCellBorders(gc, bounds, cellStyle);
			}
			
		} catch (Throwable t) {		
			if (!errorLogged) {
				System.err.println(String.format("Failed to paint: %s", t.getMessage()));
				t.printStackTrace(System.err);
				errorLogged = true;
			}
			
			//
			// Render a failure.
			//
			gc.drawImage(errorImage, bounds.x + 2, bounds.y + 2);
			gc.drawText("ERROR", bounds.x + 2 + errorImage.getBounds().width, bounds.y + 2);
		}
	}

	/**
	 * Paint the outer then inner borders of the cell.
	 */
	protected void paintCellBorders(final GC gc, final Rectangle bounds, final CellStyle cellStyle) {
		//
		// Render outer border.
		//
		gc.setAlpha(cellStyle.getForegroundOpacity());
		paintBorderLine(gc, cellStyle.getBorderOuterTop(), getTopLeft(bounds), getTopRight(bounds));
		paintBorderLine(gc, cellStyle.getBorderOuterRight(), getTopRight(bounds), getBottomRight(bounds));
		paintBorderLine(gc, cellStyle.getBorderOuterBottom(), getBottomLeft(bounds), getBottomRight(bounds));
		paintBorderLine(gc, cellStyle.getBorderOuterLeft(), getTopLeft(bounds), getBottomLeft(bounds));

		//
		// Render inner border.
		//
		setInnerBounds(bounds, cellStyle.getPaddingInnerBorder());
		paintBorderLine(gc, cellStyle.getBorderInnerTop(), getTopLeft(innerBounds), getTopRight(innerBounds));
		paintBorderLine(gc, cellStyle.getBorderInnerRight(), getTopRight(innerBounds), getBottomRight(innerBounds));
		paintBorderLine(gc, cellStyle.getBorderInnerBottom(), getBottomLeft(innerBounds), getBottomRight(innerBounds));
		paintBorderLine(gc, cellStyle.getBorderInnerLeft(), getTopLeft(innerBounds), getBottomLeft(innerBounds));
	}

	/**
	 * Paint cell image and text.
	 */
	protected void paintCellContent(final GC gc, final Rectangle bounds, final Column column, final Row<T> row, final CellStyle cellStyle) {
		gc.setAlpha(cellStyle.getForegroundOpacity());
		gc.setFont(getFont(cellStyle.getFontData()));
		gc.setForeground(getColour(cellStyle.getForeground()));

		//
		// We'll use inner bounds to indicate where the next piece of content will be allowed. Initially, it's the full
		// cell bounds, then, after the first image or text is drawn, the inner bounds will shrink/move to ensure the
		// next image/text content can't overwrite it.
		//
		// Note: This is only used if content overlap is off.
		//
		innerBounds.x = bounds.x + cellStyle.getPaddingLeft();
		innerBounds.y = bounds.y + cellStyle.getPaddingTop();
		innerBounds.width = bounds.width - cellStyle.getPaddingLeft() - cellStyle.getPaddingRight();
		innerBounds.height = bounds.height - cellStyle.getPaddingTop() - cellStyle.getPaddingBottom();

		final Rectangle oldClipping = gc.getClipping();
		gc.setClipping(innerBounds);

		//
		// Render cell image BEFORE text..
		//
		if ((row != null) && (cellStyle.getContentStyle() == ContentStyle.IMAGE || cellStyle.getContentStyle() == ContentStyle.IMAGE_THEN_TEXT)) {
			final Image image = getCellImage(column, row);
			final AlignmentStyle imageAlignment = (cellStyle.getImageAlignment() == null) ? column.getImageAlignment() : cellStyle.getImageAlignment();

			if (image != null) {
				align(image.getBounds().width, image.getBounds().height, innerBounds, imageAlignment, cellStyle);
				gc.drawImage(image, content.x, content.y);

				if (!cellStyle.isAllowContentOverlap()) {
					innerBounds.x += (image.getBounds().width + cellStyle.getPaddingImageText());
					innerBounds.width -= (image.getBounds().width + cellStyle.getPaddingImageText());
				}
			}
		}

		//
		// Render cell text.
		//
		if (cellStyle.getContentStyle() != ContentStyle.IMAGE) {
			final String text = getCellText(column, row);

			//
			// Calculate the size of the text, don't allow it to exceed the cell bounds.
			//
			if (!extentCache.containsKey(text)) {
				extentCache.put(text, gc.textExtent(text));
			}

			//
			// Ensure the sort icon in the header row does have text running through it.
			//
			int widthCap = 0;
			if (row == Row.COLUMN_HEADER_ROW && column.getSortDirection() != SortDirection.NONE) {
				final Image sortImage = getCellImage(column, row);
				widthCap = sortImage.getBounds().width + cellStyle.getPaddingImageText();
				innerBounds.width -= widthCap;
				gc.setClipping(innerBounds);
			}
			
			final Point point = extentCache.get(text);
			final int width = Math.min(point.x, (innerBounds.width - widthCap));
			final int height = Math.min(point.y, innerBounds.height);
			final AlignmentStyle textAlignment = (cellStyle.getTextAlignment() == null) ? column.getTextAlignment() : cellStyle.getTextAlignment();
			
			align(width, height, innerBounds, textAlignment, cellStyle);
			gc.drawText(text, content.x, content.y, SWT.DRAW_TRANSPARENT);
			
			if (widthCap > 0) {
				innerBounds.width += widthCap;
				gc.setClipping(innerBounds);
			}

			if (!cellStyle.isAllowContentOverlap()) {
				innerBounds.x += (width + cellStyle.getPaddingImageText());
				innerBounds.width -= (height + cellStyle.getPaddingImageText());
			}
		}

		//
		// Render cell image AFTER text..
		//
		if ((row != null) && (cellStyle.getContentStyle() == ContentStyle.TEXT_THEN_IMAGE)) {
			final Image image = getCellImage(column, row);
			final AlignmentStyle imageAlignment = (cellStyle.getImageAlignment() == null) ? column.getImageAlignment() : cellStyle.getImageAlignment();

			if (image != null) {
				align(image.getBounds().width, image.getBounds().height, innerBounds, imageAlignment, cellStyle);
				gc.drawImage(image, content.x, content.y);

				if (!cellStyle.isAllowContentOverlap()) {
					innerBounds.x += (image.getBounds().width + cellStyle.getPaddingImageText());
					innerBounds.width -= (image.getBounds().width + cellStyle.getPaddingImageText());
				}
			}
		}

		gc.setClipping(oldClipping);
	}
	
	/**
	 * Gets the text for the cell from the label provider if required.
	 */
	protected String getCellText(final Column column, final Row<T> row) {
		// TODO: Use a static harcoded column to detect this (like we do for the column header row).
		// No row or column means we're painting the row number cell.
		if (row == null || column == null) {
			// TODO: Static column for row numbers.
			return String.valueOf(rowIndex);
			
		} else if (row == Row.COLUMN_HEADER_ROW) {
			return column.getCaption();
			
		} else if (row == Row.FILTER_HEADER_ROW) {
			final QuickFilter<T> filter = getGridModel().getFilterModel().getQuickFilterForColumn(column);
			if (filter == null) {
				return "-";
			} else {
				return filter.toReadableString();
			}
						
		} else {
			return grid.getLabelProvider().getText(column, row.getElement());
		}
	}
	
	/**
	 * Return the image for the given cell.
	 */
	protected Image getCellImage(final Column column, final Row<T> row) {
		if (row == Row.FILTER_HEADER_ROW) {
			//
			// No images in the filter row.
			//
			return null;
			
		} else if (row == Row.COLUMN_HEADER_ROW) {
			//
			// Get any image from the provider
			//
			final Image image = grid.getLabelProvider().getHeaderImage(column);
			
			if (image != null) {
				return image;
			}

			//
			// Filter image if filtered.
			//
			if (getGridModel().getFilterModel().getQuickFilterForColumn(column) != null) {
				return ResourceManager.getInstance().getImage("filter_small.gif");
			}
				
			//
			// Return a sorted image if sorted.
			//
			if (column.getSortDirection() != SortDirection.NONE) {
				if (column.getSortDirection() == SortDirection.ASC){
					// TODO: Edit the lines on the sort image and to be fewer as sort order increases.
					return ResourceManager.getInstance().getImage("sort_ascending.png");
					
				} else if (column.getSortDirection() == SortDirection.DESC){
					return ResourceManager.getInstance().getImage("sort_descending.png");
				}
			}
			
		} else {		
			//
			// Get any image from the provider
			//
			return grid.getLabelProvider().getImage(column, row.getElement());
		}
		
		return null;
	}

	/**
	 * Fill the cell background. Expand the area of the fill to include any cell spacing, otherwise strips are left
	 * in the background colour of the grid.
	 */
	protected void paintCellBackground(final GC gc, final Rectangle bounds, final CellStyle cellStyle) {

		gc.setAlpha(cellStyle.getBackgroundOpacity());

		final RGB background = (alternate && (cellStyle.getBackgroundAlternate() != null)) ? cellStyle.getBackgroundAlternate() : cellStyle.getBackground();
		final RGB backgroundGradient1 = (alternate && (cellStyle.getBackgroundAlternateGradient1() != null)) ? cellStyle.getBackgroundAlternateGradient1() : cellStyle.getBackgroundGradient1();
		final RGB backgroundGradient2 = (alternate && (cellStyle.getBackgroundAlternateGradient2() != null)) ? cellStyle.getBackgroundAlternateGradient2() : cellStyle.getBackgroundGradient2();

		if (backgroundGradient1 == null || backgroundGradient2 == null) {
			//
			// Fill with no Gradient
			//
			gc.setBackground(getColour(background));
			gc.fillRectangle(bounds.x, bounds.y, bounds.width + getStyleRegistry().getCellSpacingHorizontal(), bounds.height + getStyleRegistry().getCellSpacingVertical());

		} else {
			//
			// Fill with Gradient (upper, lower).
			//
			final int halfHeight = bounds.height / 2;
			gc.setForeground(getColour(backgroundGradient1));
			gc.setBackground(getColour(background));
			gc.fillGradientRectangle(bounds.x, bounds.y, bounds.width + getStyleRegistry().getCellSpacingHorizontal(), halfHeight, true);

			gc.setForeground(getColour(background));
			gc.setBackground(getColour(backgroundGradient2));
			gc.fillGradientRectangle(bounds.x, halfHeight, bounds.width + getStyleRegistry().getCellSpacingHorizontal(), 1 + halfHeight + getStyleRegistry().getCellSpacingVertical(), true);
		}
	}

	/**
	 * Render a line in the specified border style.
	 */
	protected void paintBorderLine(final GC gc, final BorderStyle borderStyle, final Point point1, final Point point2) {

		if (borderStyle == null) {
			return;
		}

		switch (borderStyle.getLineStyle()) {
		case NONE:
			return;

		case BEVELED:
			// NOTE: Grips are currently only support on the right border side.
			gc.setForeground(getColour(RGB__SHADOW_HIGHLIGHT));
			gc.drawLine(point1.x, point1.y, point1.x, point2.y);

			gc.setForeground(getColour(RGB__SHADOW_DARK));
			gc.drawLine(point1.x - 1, point1.y, point1.x - 1, point2.y);
			break;

		case GRIP:
			// NOTE: Grips are currently only support on the right border side.
			final int spacer = new Double(((point2.y - point1.y) / 4.0) * 0.75).intValue();

			// Draw three dark dots in-between column headers.
			gc.setForeground(getColour(RGB__SHADOW_DARK));
			gc.drawPoint((point1.x-1), spacer + spacer);
			gc.drawPoint((point1.x-1), (spacer * 2) + spacer);
			gc.drawPoint((point1.x-1), (spacer * 3) + spacer);

			// Draw three light dots to make the dark ones look 3d.
			gc.setForeground(getColour(RGB__SHADOW_HIGHLIGHT));
			gc.drawPoint((point1.x-1), spacer + spacer + 1);
			gc.drawPoint((point1.x-1), (spacer * 2) + spacer + 1);
			gc.drawPoint((point1.x-1), (spacer * 3) + spacer + 1);
			break;

		case SOLID:
			gc.setForeground(getColour(borderStyle.getColour()));
			gc.setLineWidth(borderStyle.getWidth());
			gc.setLineStyle(SWT.LINE_SOLID);
			gc.drawLine(point1.x, point1.y, point2.x, point2.y);
			break;

		case DOTS:
			gc.setForeground(getColour(borderStyle.getColour()));
			gc.setLineWidth(borderStyle.getWidth());
			gc.setLineStyle(SWT.LINE_DOT);
			gc.drawLine(point1.x, point1.y, point2.x, point2.y);
			break;

		case DASHED:
			gc.setForeground(getColour(borderStyle.getColour()));
			gc.setLineWidth(borderStyle.getWidth());
			gc.setLineStyle(SWT.LINE_DASH);
			gc.drawLine(point1.x, point1.y, point2.x, point2.y);
			break;

		case DASH_DOT:
			gc.setForeground(getColour(borderStyle.getColour()));
			gc.setLineWidth(borderStyle.getWidth());
			gc.setLineStyle(SWT.LINE_DASHDOT);
			gc.drawLine(point1.x, point1.y, point2.x, point2.y);
			break;

		case DASH_DOT_DOT:
			gc.setForeground(getColour(borderStyle.getColour()));
			gc.setLineWidth(borderStyle.getWidth());
			gc.setLineStyle(SWT.LINE_DASHDOTDOT);
			gc.drawLine(point1.x, point1.y, point2.x, point2.y);
			break;
		}
	}

	protected GridModel<T> getGridModel() {
		return grid.getGridModel();
	}

	protected StyleRegistry<T> getStyleRegistry() {
		return getGridModel().getStyleRegistry();
	}

//	protected Viewport getViewport() {
//		return grid.getViewport();
//	}

	protected Font getFont(final FontData fontData) {
		return ResourceManager.getInstance().getFont(fontData);
	}

	protected Color getColour(final RGB rgb) {
		return ResourceManager.getInstance().getColour(rgb);
	}

	protected Point getTopLeft(final Rectangle rectangle) {
		topLeft.x = rectangle.x;
		topLeft.y = rectangle.y;
		return topLeft;
	}

	protected Point getTopRight(final Rectangle rectangle) {
		topRight.x = rectangle.x + rectangle.width;
		topRight.y = rectangle.y;
		return topRight;
	}

	protected Point getBottomLeft(final Rectangle rectangle) {
		bottomLeft.x = rectangle.x;
		bottomLeft.y = rectangle.y + rectangle.height;
		return bottomLeft;
	}

	protected Point getBottomRight(final Rectangle rectangle) {
		bottomRight.x = rectangle.x + rectangle.width;
		bottomRight.y = rectangle.y + rectangle.height;
		return bottomRight;
	}

	protected void setInnerBounds(final Rectangle original, final int delta) {
		innerBounds.x = original.x + delta;
		innerBounds.y = original.y + delta;
		innerBounds.width = original.width - (delta * 2);
		innerBounds.height = original.height - (delta * 2);
	}

	/**
	 * Aligns the dimensions within the bounds specified and updates the content pointer with the top-left
	 * corner where the rectangle should be drawn.
	 */
	protected void align(final int width, final int height, final Rectangle bounds, final AlignmentStyle alignment, final CellStyle cellStyle) {

		switch (alignment) {
		case BOTTOM_CENTER:
			content.x = bounds.x + ((bounds.width - width) / 2);
			content.y = bounds.y + (bounds.height - height);
			break;

		case BOTTOM_LEFT:
			content.x = bounds.x + cellStyle.getPaddingLeft();
			content.y = bounds.y + (bounds.height - height);
			break;

		case BOTTOM_RIGHT:
			content.x = bounds.x + (bounds.width - width);
			content.y = bounds.y + (bounds.height - height);
			break;

		case CENTER:
			content.x = bounds.x + ((bounds.width - width) / 2);
			content.y = bounds.y + ((bounds.height - height) / 2);
			break;

		case LEFT_CENTER:
			content.x = bounds.x;
			content.y = bounds.y + ((bounds.height - height) / 2);
			break;

		case RIGHT_CENTER:
			content.x = bounds.x + (bounds.width - width);
			content.y = bounds.y + ((bounds.height - height) / 2);
			break;

		case TOP_CENTER:
			content.x = bounds.x + ((bounds.width - width) / 2);
			content.y = bounds.y;
			break;

		case TOP_LEFT:
			content.x = bounds.x;
			content.y = bounds.y;
			break;

		case TOP_RIGHT:
			content.x = bounds.x + (bounds.width - width);
			content.y = bounds.y;
			break;

		default:
			System.out.println("No alignment set!");
		}
	}
}