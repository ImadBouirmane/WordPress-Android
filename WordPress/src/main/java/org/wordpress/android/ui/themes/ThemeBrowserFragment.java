package org.wordpress.android.ui.themes;

import android.app.Activity;
import android.app.Fragment;
import android.database.Cursor;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView.RecyclerListener;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;

import com.android.volley.VolleyError;
import com.android.volley.toolbox.ImageLoader.ImageContainer;
import com.android.volley.toolbox.ImageLoader.ImageListener;
import com.android.volley.toolbox.NetworkImageView;

import org.wordpress.android.R;
import org.wordpress.android.WordPress;
import org.wordpress.android.models.Theme;
import org.wordpress.android.ui.themes.ThemeBrowserAdapter.ScreenshotHolder;
import org.wordpress.android.util.NetworkUtils;
import org.wordpress.android.util.helpers.SwipeToRefreshHelper;
import org.wordpress.android.util.helpers.SwipeToRefreshHelper.RefreshListener;
import org.wordpress.android.util.widgets.CustomSwipeRefreshLayout;
import org.wordpress.android.widgets.HeaderGridView;

/**
 * A fragment display the themes on a grid view.
 */
public class ThemeBrowserFragment extends Fragment implements OnItemClickListener, RecyclerListener {
    public enum ThemeSortType {
        TRENDING("Trending"),
        NEWEST("Newest"),
        POPULAR("Popular");

        private String mTitle;

        private ThemeSortType(String title) {
            mTitle = title;
        }

        public String getTitle() {
            return mTitle;
        }

        public static ThemeSortType getTheme(int position) {
            if (position < ThemeSortType.values().length)
                return ThemeSortType.values()[position];
            else
                return TRENDING;
        }
    }

    public interface ThemeBrowserFragmentCallback {
        void onThemeSelected(String themeId);
    }

    protected static final String ARGS_SORT = "ARGS_SORT";
    protected static final String BUNDLE_SCROLL_POSTION = "BUNDLE_SCROLL_POSTION";

    protected HeaderGridView mGridView;
    protected TextView mEmptyView;
    protected TextView mNoResultText;
    protected ThemeBrowserAdapter mAdapter;
    protected ThemeBrowserFragmentCallback mCallback;
    protected int mSavedScrollPosition = 0;
    private boolean mShouldRefreshOnStart;
    private SwipeToRefreshHelper mSwipeToRefreshHelper;

    public static ThemeBrowserFragment newInstance(ThemeSortType sort) {
        ThemeBrowserFragment fragment = new ThemeBrowserFragment();
        Bundle args = new Bundle();
        args.putInt(ARGS_SORT, sort.ordinal());
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);

        try {
            mCallback = (ThemeBrowserFragmentCallback) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement ThemeBrowserFragmentCallback");
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.theme_browser_fragment, container, false);

        setRetainInstance(true);

        mNoResultText = (TextView) view.findViewById(R.id.theme_no_search_result_text);
        mEmptyView = (TextView) view.findViewById(R.id.text_empty);
        configureGridView(inflater, view);

        // swipe to refresh setup but not for the search view
        if (!(this instanceof ThemeSearchFragment)) {
            mSwipeToRefreshHelper = new SwipeToRefreshHelper(getActivity(), (CustomSwipeRefreshLayout) view.findViewById(
                    R.id.ptr_layout), new RefreshListener() {
                @Override
                public void onRefreshStarted() {
                    if (!isAdded()) {
                        return;
                    }
                    if (!NetworkUtils.checkConnection(getActivity())) {
                        mSwipeToRefreshHelper.setRefreshing(false);
                        mEmptyView.setText(R.string.no_network_title);
                        return;
                    }
                    if (getActivity() instanceof ThemeBrowserActivity) {
                        ((ThemeBrowserActivity) getActivity()).fetchThemes();
                    }
                }
            });
            mSwipeToRefreshHelper.setRefreshing(mShouldRefreshOnStart);
        }
        restoreState(savedInstanceState);
        return view;
    }

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        Cursor cursor = fetchThemes(getThemeSortType());
        if (cursor == null) {
            return;
        }
        mAdapter = new ThemeBrowserAdapter(getActivity(), cursor, false);
        setEmptyViewVisible(mAdapter.getCount() == 0);
        mGridView.setAdapter(mAdapter);
        mGridView.setOnItemClickListener(this);
        mGridView.setSelection(mSavedScrollPosition);
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mGridView != null)
            outState.putInt(BUNDLE_SCROLL_POSTION, mGridView.getFirstVisiblePosition());
    }

    private void configureGridView(LayoutInflater inflater, View view) {
        mGridView = (HeaderGridView) view.findViewById(R.id.theme_listview);
        addHeaderViews(inflater);
        mGridView.setRecyclerListener(this);
    }

    private void addHeaderViews(LayoutInflater inflater) {
        addMainHeader(inflater);
        configureAndAddSearchHeader(inflater);
    }

    private void configureAndAddSearchHeader(LayoutInflater inflater) {
        View headerSearch = inflater.inflate(R.layout.theme_grid_cardview_header_search, null);
        Spinner filterSpinner = (Spinner) headerSearch.findViewById(R.id.theme_filter_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(getActivity(), R.array.themes_filter_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        filterSpinner.setAdapter(adapter);
        mGridView.addHeaderView(headerSearch);
    }

    private String getBlogId() {
        if (WordPress.getCurrentBlog() == null)
            return "0";
        return String.valueOf(WordPress.getCurrentBlog().getRemoteBlogId());
    }

    private void addMainHeader(LayoutInflater inflater) {
        View header = inflater.inflate(R.layout.theme_grid_cardview_header, null);
        setCurrentThemeHeaderText(header);
        mGridView.addHeaderView(header);
    }

    private void setCurrentThemeHeaderText(View header) {
        String currentTheme = WordPress.wpDB.getCurrentThemeId(getBlogId());
        TextView currentThemeTextView = (TextView) header.findViewById(R.id.header_theme_text);
        currentThemeTextView.setText(currentTheme);
    }

    public void setRefreshing(boolean refreshing) {
        mShouldRefreshOnStart = refreshing;
        if (mSwipeToRefreshHelper != null) {
            mSwipeToRefreshHelper.setRefreshing(refreshing);
            if (!refreshing) {
                refreshView();
            }
        }
    }

    private void restoreState(Bundle savedInstanceState) {
        if (savedInstanceState != null) {
            mSavedScrollPosition = savedInstanceState.getInt(BUNDLE_SCROLL_POSTION, 0);
        }
    }

    private void setEmptyViewVisible(boolean visible) {
        if (getView() == null || !isAdded()) {
            return;
        }
        mEmptyView.setVisibility(visible ? View.VISIBLE : View.GONE);
        mGridView.setVisibility(visible ? View.GONE : View.VISIBLE);
        if (visible && !NetworkUtils.isNetworkAvailable(getActivity())) {
            mEmptyView.setText(R.string.no_network_title);
        }
    }

    private ThemeSortType getThemeSortType() {
        int sortType = ThemeSortType.TRENDING.ordinal();
        if (getArguments() != null && getArguments().containsKey(ARGS_SORT))  {
            sortType = getArguments().getInt(ARGS_SORT);
        }

        return ThemeSortType.getTheme(sortType);
    }

    /**
     * Fetch themes for a given ThemeSortType.
     *
     * @return a db Cursor or null if current blog is null
     */
    private Cursor fetchThemes(ThemeSortType themeSortType) {
        if (WordPress.getCurrentBlog() == null) {
            return null;
        }
        String blogId = String.valueOf(WordPress.getCurrentBlog().getRemoteBlogId());
        switch (themeSortType) {
            case POPULAR:
                return WordPress.wpDB.getThemesPopularity(blogId);
            case NEWEST:
                return WordPress.wpDB.getThemesNewest(blogId);
            case TRENDING:
            default:
                return WordPress.wpDB.getThemesTrending(blogId);
        }
    }

    private void refreshView() {
        Cursor cursor = fetchThemes(getThemeSortType());
        if (cursor == null) {
            return;
        }
        if (mAdapter == null) {
            mAdapter = new ThemeBrowserAdapter(getActivity(), cursor, false);
        }
        if (mNoResultText.isShown()) {
            mNoResultText.setVisibility(View.GONE);
        }
        mAdapter.changeCursor(cursor);
        setEmptyViewVisible(mAdapter.getCount() == 0);
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Cursor cursor = ((ThemeBrowserAdapter) parent.getAdapter()).getCursor();
        String themeId = cursor.getString(cursor.getColumnIndex("themeId"));
        mCallback.onThemeSelected(themeId);
    }

    @Override
    public void onMovedToScrapHeap(View view) {
        // cancel image fetch requests if the view has been moved to recycler.

        NetworkImageView niv = (NetworkImageView) view.findViewById(R.id.theme_grid_item_image);
        if (niv != null) {
            // this tag is set in the ThemeBrowserAdapter class
            ScreenshotHolder tag =  (ScreenshotHolder) niv.getTag();
            if (tag != null && tag.requestURL != null) {
                // need a listener to cancel request, even if the listener does nothing
                ImageContainer container = WordPress.imageLoader.get(tag.requestURL, new ImageListener() {
                    @Override
                    public void onErrorResponse(VolleyError error) { }

                    @Override
                    public void onResponse(ImageContainer response, boolean isImmediate) { }

                });
                container.cancelRequest();
            }
        }
    }
}
