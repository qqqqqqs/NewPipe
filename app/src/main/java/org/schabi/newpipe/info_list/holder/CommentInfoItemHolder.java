package org.schabi.newpipe.info_list.holder;

import static android.text.TextUtils.isEmpty;
import static org.schabi.newpipe.util.ServiceHelper.getServiceById;

import android.graphics.Paint;
import android.text.Layout;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.text.HtmlCompat;
import androidx.fragment.app.FragmentActivity;

import org.schabi.newpipe.R;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.info_list.InfoItemBuilder;
import org.schabi.newpipe.local.history.HistoryRecordManager;
import org.schabi.newpipe.util.DeviceUtils;
import org.schabi.newpipe.util.Localization;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.image.ImageStrategy;
import org.schabi.newpipe.util.image.PicassoHelper;
import org.schabi.newpipe.util.external_communication.ShareUtils;
import org.schabi.newpipe.util.text.CommentTextOnTouchListener;
import org.schabi.newpipe.util.text.TextLinkifier;

import java.util.function.Consumer;

import io.reactivex.rxjava3.disposables.CompositeDisposable;

public class CommentInfoItemHolder extends InfoItemHolder {
    private static final String ELLIPSIS = "…";

    private static final int COMMENT_DEFAULT_LINES = 2;
    private static final int COMMENT_EXPANDED_LINES = 1000;

    private final int commentHorizontalPadding;
    private final int commentVerticalPadding;

    private final Paint paintAtContentSize;
    private final float ellipsisWidthPx;

    private final RelativeLayout itemRoot;
    private final ImageView itemThumbnailView;
    private final TextView itemContentView;
    private final ImageView itemThumbsUpView;
    private final TextView itemLikesCountView;
    private final TextView itemTitleView;
    private final ImageView itemHeartView;
    private final ImageView itemPinnedView;
    private final Button repliesButton;

    private final CompositeDisposable disposables = new CompositeDisposable();
    @Nullable
    private Description commentText;
    @Nullable
    private StreamingService streamService;
    @Nullable
    private String streamUrl;

    public CommentInfoItemHolder(final InfoItemBuilder infoItemBuilder,
                                 final ViewGroup parent) {
        super(infoItemBuilder, R.layout.list_comment_item, parent);

        itemRoot = itemView.findViewById(R.id.itemRoot);
        itemThumbnailView = itemView.findViewById(R.id.itemThumbnailView);
        itemContentView = itemView.findViewById(R.id.itemCommentContentView);
        itemThumbsUpView = itemView.findViewById(R.id.detail_thumbs_up_img_view);
        itemLikesCountView = itemView.findViewById(R.id.detail_thumbs_up_count_view);
        itemTitleView = itemView.findViewById(R.id.itemTitleView);
        itemHeartView = itemView.findViewById(R.id.detail_heart_image_view);
        itemPinnedView = itemView.findViewById(R.id.detail_pinned_view);
        repliesButton = itemView.findViewById(R.id.replies_button);

        commentHorizontalPadding = (int) infoItemBuilder.getContext()
                .getResources().getDimension(R.dimen.comments_horizontal_padding);
        commentVerticalPadding = (int) infoItemBuilder.getContext()
                .getResources().getDimension(R.dimen.comments_vertical_padding);

        paintAtContentSize = new Paint();
        paintAtContentSize.setTextSize(itemContentView.getTextSize());
        ellipsisWidthPx = paintAtContentSize.measureText(ELLIPSIS);
    }

    @Override
    public void updateFromItem(final InfoItem infoItem,
                               final HistoryRecordManager historyRecordManager) {
        if (!(infoItem instanceof CommentsInfoItem)) {
            return;
        }
        final CommentsInfoItem item = (CommentsInfoItem) infoItem;


        // load the author avatar
        PicassoHelper.loadAvatar(item.getUploaderAvatars()).into(itemThumbnailView);
        if (ImageStrategy.shouldLoadImages()) {
            itemThumbnailView.setVisibility(View.VISIBLE);
            itemRoot.setPadding(commentVerticalPadding, commentVerticalPadding,
                    commentVerticalPadding, commentVerticalPadding);
        } else {
            itemThumbnailView.setVisibility(View.GONE);
            itemRoot.setPadding(commentHorizontalPadding, commentVerticalPadding,
                    commentHorizontalPadding, commentVerticalPadding);
        }
        itemThumbnailView.setOnClickListener(view -> openCommentAuthor(item));


        // setup the top row, with pinned icon, author name and comment date
        itemPinnedView.setVisibility(item.isPinned() ? View.VISIBLE : View.GONE);
        itemTitleView.setText(Localization.concatenateStrings(item.getUploaderName(),
                Localization.relativeTimeOrTextual(itemBuilder.getContext(), item.getUploadDate(),
                        item.getTextualUploadDate())));


        // setup bottom row, with likes, heart and replies button
        itemLikesCountView.setText(
                Localization.likeCount(itemBuilder.getContext(), item.getLikeCount()));

        itemHeartView.setVisibility(item.isHeartedByUploader() ? View.VISIBLE : View.GONE);

        final boolean hasReplies = item.getReplies() != null;
        repliesButton.setOnClickListener(hasReplies ? v -> openCommentReplies(item) : null);
        repliesButton.setVisibility(hasReplies ? View.VISIBLE : View.GONE);
        repliesButton.setText(hasReplies
                ? Localization.replyCount(itemBuilder.getContext(), item.getReplyCount()) : "");
        ((RelativeLayout.LayoutParams) itemThumbsUpView.getLayoutParams()).topMargin =
                hasReplies ? 0 : DeviceUtils.dpToPx(6, itemBuilder.getContext());


        // setup comment content and click listeners to expand/ellipsize it
        streamService = getServiceById(item.getServiceId());
        streamUrl = item.getUrl();
        commentText = item.getCommentText();
        ellipsize();

        //noinspection ClickableViewAccessibility
        itemContentView.setOnTouchListener(CommentTextOnTouchListener.INSTANCE);

        itemView.setOnClickListener(view -> {
            toggleEllipsize();
            if (itemBuilder.getOnCommentsSelectedListener() != null) {
                itemBuilder.getOnCommentsSelectedListener().selected(item);
            }
        });

        itemView.setOnLongClickListener(view -> {
            if (DeviceUtils.isTv(itemBuilder.getContext())) {
                openCommentAuthor(item);
            } else {
                final CharSequence text = itemContentView.getText();
                if (text != null) {
                    ShareUtils.copyToClipboard(itemBuilder.getContext(), text.toString());
                }
            }
            return true;
        });
    }

    private void openCommentAuthor(@NonNull final CommentsInfoItem item) {
        NavigationHelper.openCommentAuthorIfPresent((FragmentActivity) itemBuilder.getContext(),
                item);
    }

    private void openCommentReplies(@NonNull final CommentsInfoItem item) {
        NavigationHelper.openCommentRepliesFragment((FragmentActivity) itemBuilder.getContext(),
                item);
    }

    private void allowLinkFocus() {
        itemContentView.setMovementMethod(LinkMovementMethod.getInstance());
    }

    private void denyLinkFocus() {
        itemContentView.setMovementMethod(null);
    }

    private boolean shouldFocusLinks() {
        if (itemView.isInTouchMode()) {
            return false;
        }

        final URLSpan[] urls = itemContentView.getUrls();

        return urls != null && urls.length != 0;
    }

    private void determineMovementMethod() {
        if (shouldFocusLinks()) {
            allowLinkFocus();
        } else {
            denyLinkFocus();
        }
    }

    private void ellipsize() {
        itemContentView.setMaxLines(COMMENT_EXPANDED_LINES);
        linkifyCommentContentView(v -> {
            boolean hasEllipsis = false;

            final CharSequence charSeqText = itemContentView.getText();
            if (charSeqText != null && itemContentView.getLineCount() > COMMENT_DEFAULT_LINES) {
                // Note that converting to String removes spans (i.e. links), but that's something
                // we actually want since when the text is ellipsized we want all clicks on the
                // comment to expand the comment, not to open links.
                final String text = charSeqText.toString();

                final Layout layout = itemContentView.getLayout();
                final float lineWidth = layout.getLineWidth(COMMENT_DEFAULT_LINES - 1);
                final float layoutWidth = layout.getWidth();
                final int lineStart = layout.getLineStart(COMMENT_DEFAULT_LINES - 1);
                final int lineEnd = layout.getLineEnd(COMMENT_DEFAULT_LINES - 1);

                // remove characters up until there is enough space for the ellipsis
                // (also summing 2 more pixels, just to be sure to avoid float rounding errors)
                int end = lineEnd;
                float removedCharactersWidth = 0.0f;
                while (lineWidth - removedCharactersWidth + ellipsisWidthPx + 2.0f > layoutWidth
                        && end >= lineStart) {
                    end -= 1;
                    // recalculate each time to account for ligatures or other similar things
                    removedCharactersWidth = paintAtContentSize.measureText(
                            text.substring(end, lineEnd));
                }

                // remove trailing spaces and newlines
                while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
                    end -= 1;
                }

                final String newVal = text.substring(0, end) + ELLIPSIS;
                itemContentView.setText(newVal);
                hasEllipsis = true;
            }

            itemContentView.setMaxLines(COMMENT_DEFAULT_LINES);
            if (hasEllipsis) {
                denyLinkFocus();
            } else {
                determineMovementMethod();
            }
        });
    }

    private void toggleEllipsize() {
        final CharSequence text = itemContentView.getText();
        if (!isEmpty(text) && text.charAt(text.length() - 1) == ELLIPSIS.charAt(0)) {
            expand();
        } else if (itemContentView.getLineCount() > COMMENT_DEFAULT_LINES) {
            ellipsize();
        }
    }

    private void expand() {
        itemContentView.setMaxLines(COMMENT_EXPANDED_LINES);
        linkifyCommentContentView(v -> determineMovementMethod());
    }

    private void linkifyCommentContentView(@Nullable final Consumer<TextView> onCompletion) {
        disposables.clear();
        if (commentText != null) {
            TextLinkifier.fromDescription(itemContentView, commentText,
                    HtmlCompat.FROM_HTML_MODE_LEGACY, streamService, streamUrl, disposables,
                    onCompletion);
        }
    }
}
