package org.thoughtcrime.securesms.map;

import android.content.Intent;
import android.graphics.PointF;
import android.os.Bundle;
import android.support.design.widget.BottomSheetBehavior;
import android.support.v4.app.FragmentTransaction;
import android.support.v7.widget.SwitchCompat;
import android.util.Log;
import android.view.View;
import android.widget.RelativeLayout;

import com.b44t.messenger.DcMsg;
import com.mapbox.geojson.Feature;
import com.mapbox.mapboxsdk.camera.CameraPosition;
import com.mapbox.mapboxsdk.camera.CameraUpdateFactory;
import com.mapbox.mapboxsdk.geometry.LatLng;
import com.mapbox.mapboxsdk.maps.MapboxMap;
import com.mapbox.mapboxsdk.maps.Style;

import org.thoughtcrime.securesms.ApplicationContext;
import org.thoughtcrime.securesms.BaseActivity;
import org.thoughtcrime.securesms.ConversationActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.components.rangeslider.TimeRangeSlider;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.geolocation.DcLocation;
import org.thoughtcrime.securesms.util.Prefs;

import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.Random;

import static android.support.design.widget.BottomSheetBehavior.STATE_COLLAPSED;
import static android.support.design.widget.BottomSheetBehavior.STATE_EXPANDED;
import static com.b44t.messenger.DcChat.DC_CHAT_NO_CHAT;
import static com.mapbox.mapboxsdk.constants.MapboxConstants.MINIMUM_ZOOM;
import static org.thoughtcrime.securesms.map.MapDataManager.MARKER_SELECTED;
import static org.thoughtcrime.securesms.map.MapDataManager.MESSAGE_ID;
import static org.thoughtcrime.securesms.map.model.MapSource.INFO_WINDOW_LAYER;

public class MapActivity extends BaseActivity implements Observer, TimeRangeSlider.OnTimestampChangedListener {

    public static final String TAG = MapActivity.class.getSimpleName();
    public static final String CHAT_ID = "chat_id";
    public static final String CHAT_IDS = "chat_id";
    public static final String MAP_TAG = "org.thoughtcrime.securesms.map";

    private DcLocation dcLocation;
    private MapDataManager mapDataManager;
    private MapboxMap mapboxMap;
    DCMapFragment mapFragment;
    MarkerViewManager markerViewManager;
    private int chatId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_map);
        chatId =  getIntent().getIntExtra(CHAT_ID, -1);

        if (chatId == -1) {
            finish();
            return;
        }

        dcLocation = DcLocation.getInstance();

        if (savedInstanceState == null) {
            final FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
            mapFragment = DCMapFragment.newInstance();
            transaction.add(R.id.container, mapFragment, MAP_TAG);
            transaction.commit();
        } else {
            mapFragment = (DCMapFragment) getSupportFragmentManager().findFragmentByTag(MAP_TAG);
        }

        mapFragment.getMapAsync(mapboxMap -> mapboxMap.setStyle(Style.MAPBOX_STREETS, style -> {

            this.mapboxMap = mapboxMap;
            this.markerViewManager = new MarkerViewManager(mapFragment.getMapView(), mapboxMap);


            final LatLng lastMapCenter = Prefs.getMapCenter(this.getApplicationContext(), chatId);
            if (lastMapCenter != null) {
                double lastZoom = Prefs.getMapZoom(this.getApplicationContext(), chatId);
                mapboxMap.setCameraPosition(new CameraPosition.Builder()
                        .target(lastMapCenter)
                        .zoom(lastZoom)
                        .build());
            } else if (dcLocation.getLastLocation().getProvider().equals("?")) {
                double randomLongitude = getRandomLongitude();
                mapboxMap.setCameraPosition(new CameraPosition.Builder()
                        .target(new LatLng(0d, randomLongitude))
                        .zoom(MINIMUM_ZOOM)
                        .build());
            } else {
                mapboxMap.setCameraPosition(new CameraPosition.Builder()
                        .target(new LatLng(dcLocation.getLastLocation().getLatitude(), dcLocation.getLastLocation().getLongitude()))
                        .zoom(9)
                        .build());
            }

            mapboxMap.getUiSettings().setLogoEnabled(false);
            mapboxMap.getUiSettings().setAttributionEnabled(false);

            Style mapBoxStyle = mapboxMap.getStyle();
            if (mapBoxStyle == null) {
                return;
            }

            mapDataManager = new MapDataManager(this, mapBoxStyle, chatId, (latLngBounds) -> {
                Log.d(TAG, "on Data initialized");
                if (latLngBounds != null && lastMapCenter == null) {
                    mapboxMap.easeCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, 50), 1000);
                }

                mapboxMap.addOnMapClickListener(point ->
                        handleInfoWindowClick(point) ||
                        handleMarkerClick(point) ||
                        handleAddPoiClick(point));

                /*if (BuildConfig.DEBUG) {
                    mapboxMap.addOnMapLongClickListener(point -> {
                        new AlertDialog.Builder(MapActivity.this)
                                .setMessage(getString(R.string.menu_delete_locations))
                                .setPositiveButton(R.string.yes, (dialog, which) -> {
                                    ApplicationContext.getInstance(MapActivity.this).dcLocationManager.deleteAllLocations();
                                })
                                .setNegativeButton(R.string.no, null)
                                .show();
                        return true;
                    });
                }*/

                SwitchCompat switchCompat = this.findViewById(R.id.locationTraceSwitch);
                switchCompat.setOnCheckedChangeListener((buttonView, isChecked) -> {
                    mapDataManager.showTraces(isChecked);
                });
            });

            TimeRangeSlider timeRangeSlider = this.findViewById(R.id.timeRangeSlider);
            timeRangeSlider.setOnTimestampChangedListener(this);

        }));

        View bottomSheet = this.findViewById(R.id.bottom_sheet);
        BottomSheetBehavior behavior = BottomSheetBehavior.from(bottomSheet);

        RelativeLayout bottomSheetSlider = this.findViewById(R.id.bottomSheetSlider);
        bottomSheetSlider.setOnClickListener(v -> {
            switch (behavior.getState()) {
                case STATE_EXPANDED:
                    behavior.setState(STATE_COLLAPSED);
                    break;
                default:
                    behavior.setState(STATE_EXPANDED);
                    break;
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        DcLocation.getInstance().addObserver(this);
        if (mapDataManager != null) {
            mapDataManager.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        DcLocation.getInstance().deleteObserver(this);
        if (mapDataManager != null) {
            mapDataManager.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mapDataManager != null) {
            Prefs.setMapCenter(this.getApplicationContext(), mapDataManager.getChatId(), mapboxMap.getCameraPosition().target);
            Prefs.setMapZoom(this.getApplicationContext(), mapDataManager.getChatId(), mapboxMap.getCameraPosition().zoom);
            mapDataManager.onDestroy();
        }
        if (markerViewManager != null) {
            markerViewManager.onDestroy();
        }
    }

    @Override
    public void update(Observable o, Object arg) {
        if (o instanceof DcLocation) {
            this.dcLocation = (DcLocation) o;
            Log.d(TAG, "show marker on map: " +
                    dcLocation.getLastLocation().getLatitude() + ", " +
                    dcLocation.getLastLocation().getLongitude());
            //TODO: consider implementing a button -> center map to current location
        }
    }

    @Override
    public void onTimestampChanged(long startTimestamp, long stopTimestamp) {
        if (this.mapboxMap == null) {
            return;
        }
        mapDataManager.filterRange(startTimestamp, stopTimestamp);

    }

    @Override
    public void onFilterLastPosition(long startTimestamp) {
        if (this.mapboxMap == null) {
            return;
        }
        mapDataManager.filterLastPositions(startTimestamp);
    }

    private double getRandomLongitude() {
        double start = -180;
        double end = 180;
        double random = new Random().nextDouble();
        return start + (random * (end - start));
    }

    private boolean handleMarkerClick(LatLng point) {
        final PointF pixel = mapboxMap.getProjection().toScreenLocation(point);
        Log.d(TAG, "on item clicked.");

        List<Feature> features = mapboxMap.queryRenderedFeatures(pixel, mapDataManager.getMarkerLayers());
        for (Feature feature : features) {
            Log.d(TAG, "found feature: " + feature.toJson());
            //show first feature that has meta data infos
            if (feature.hasProperty(MARKER_SELECTED))  {
                mapDataManager.setMarkerSelected(feature.id());
                if (markerViewManager.hasMarkers()) {
                    markerViewManager.removeMarkers();
                }
                return true;
            }
        }
        return mapDataManager.unselectMarker();
    }

    private boolean handleInfoWindowClick(LatLng point) {
        final PointF pixel = mapboxMap.getProjection().toScreenLocation(point);

        List<Feature> features = mapboxMap.queryRenderedFeatures(pixel, INFO_WINDOW_LAYER);
        Log.d(TAG, "on info window clicked." + features.size());

        for (Feature feature : features) {
            Log.d(TAG, "found feature: " + feature.toJson());

            int messageId = feature.getNumberProperty(MESSAGE_ID).intValue();
            DcMsg dcMsg = ApplicationContext.getInstance(this).dcContext.getMsg(messageId);
            int dcMsgChatId = dcMsg.getChatId();
            if (dcMsgChatId == DC_CHAT_NO_CHAT) {
                continue;
            }

            int msgs[] = DcHelper.getContext(MapActivity.this).getChatMsgs(dcMsgChatId, 0, 0);
            int startingPosition = -1;
            for(int i=0; i< msgs.length; i++ ) {
                if(msgs[i] == messageId) {
                    startingPosition = msgs.length-1-i;
                    break;
                }
            }

            Intent intent = new Intent(MapActivity.this, ConversationActivity.class);
            intent.putExtra(ConversationActivity.THREAD_ID_EXTRA, dcMsgChatId);
            intent.putExtra(ConversationActivity.LAST_SEEN_EXTRA, 0);
            intent.putExtra(ConversationActivity.STARTING_POSITION_EXTRA, startingPosition);
            startActivity(intent);
            return true;

        }
        return false;
    }

    private boolean handleAddPoiClick(LatLng point) {
        if (markerViewManager.hasMarkers()) {
            markerViewManager.removeMarkers();
        } else {
            AddPoiView addPoiView = new AddPoiView(this);
            addPoiView.setLatLng(point);
            addPoiView.setChatId(chatId);
            addPoiView.setOnMessageSentListener(markerViewManager);
            markerViewManager.addMarker(new MarkerView(point, addPoiView));
        }
        return true;
    }

}
