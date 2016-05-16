package com.dragonhack.matic.hackapong;

/**
 * Created by matic on 14. 05. 2016.
 */

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Paint.Style;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.View.OnTouchListener;
import android.widget.Toast;

import com.thalmic.myo.Vector3;

/**
 * This class is the main viewing window for the Pong game. All the game's
 * logic takes place within this class as well.
 *
 * @author OEP
 */
public class PongView extends View implements OnTouchListener, OnKeyListener, OnCompletionListener {
    private static final String TAG = "PongView";

    /**
     * This is mostly deprecated but kept around if the need
     * to add more game states comes around.
     */
    private State mCurrentState = State.Running;
    private State mLastState = State.Stopped;

    public static enum State {Running, Stopped}

    private boolean initialized = false;        // Exists to initialize the View when getWidth() and getHeight() are known
    private boolean requestNewRound = true;        // Setting this to true will start a new round on the next game loop pass
    private boolean showTitle = true;            // Overlay the Pong logo over a computerized game
    private boolean mContinue = true;            // Set this to false to KILL THE THREAD.
    private boolean mMuted = false;                // Mute sounds.

    /**
     * These variables concern the paddles, controlling their touch zones, lives, last
     * position where the player touched, and whether or not the paddle is controlled by
     * AI.
     */
    private Rect mRedPaddleRect = new Rect();
    private Rect mBluePaddleRect = new Rect();
    private Rect mRedTouchBox, mBlueTouchBox, mPauseTouchBox;
    private float mRedLastTouch = 0;
    private float mBlueLastTouch = 0;
    private int mRedLives;
    private int mBlueLives;
    private boolean mRedIsPlayer = false;
    private boolean mBlueIsPlayer = false;

    /**
     * Controls the framerate of the game. mFrameSkips is amount to increment mFramesPerSecond when
     * the paddle hits the ball. As the game progresses, the framerate will speed up to make it more
     * difficult for human players.
     */
    private int mFramesPerSecond = 45;
    private int mFrameSkips = 5;
    private long mLastFrame = 0;

    /**
     * The Ball variables. mDX/mDY are set after the position of the ball is manipulated to get
     * how much dx or dy the ball has accomplished. mBallCounter when >0 means the ball is held
     * still and will blink for a while.
     */
    private Point mBallPosition;
    private int mBallAngle;
    private int mDX;
    private int mDY;
    private int mBallCounter = 60;
    private int mBallSpeed = 10;
    private int mPaddleSpeed = mBallSpeed - 2;

    /**
     * Who doesn't love random numbers?
     */
    private static final Random RNG = new Random();

    private final Paint mPaint = new Paint();

    /**
     * These static variables control a few constants for the game.
     */
    private static final int BALL_RADIUS = 10;
    private static final int PADDLE_THICKNESS = 15;
    private static final int PADDLE_WIDTH = 120;
    private static final int PADDING = 3;
    private static final int SCROLL_SENSITIVITY = 80;

    /**
     * Controls how fast we refresh
     */
    private RefreshHandler mRedrawHandler = new RefreshHandler();

    private MediaPlayer mWallHit, mPaddleHit;
    private MediaPlayer mMissTone;
    private MediaPlayer mWinTone;

    /**
     * Myo states
     */
    private boolean myoRedLeft  = false;
    private boolean myoRedRight = false;
    private boolean myoRedRest  = true;

    private boolean myoBlueLeft  = false;
    private boolean myoBlueRight = false;
    private boolean myoBlueRest  = true;

    /**
     * Myo consts and vars
     */
    private static final int myoMoveStep = 5;

    private int hits1 = 0;
    private int hits2 = 0;
    private int wins1 = 0;
    private int wins2 = 0;

    private Vector3 mRedAcc = new Vector3();
    private Vector3 mBlueAcc = new Vector3();
    private double mRedX = getWidth() / 2;
    private double mBlueX = getWidth() / 2;
    private double mRedSpeed = 0;
    private double mBlueSpeed = 0;
    private double mFriction = 0.95;
    private double mMagnet = 3;
    private double mStrength = 3;

    /**
     * An overloaded class that repaints this view in a separate thread.
     * Calling PongView.update() should initiate the thread.
     *
     * @author OEP
     */
    class RefreshHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            PongView.this.update();
            PongView.this.invalidate(); // Mark the view as 'dirty'
        }

        public void sleep(long delay) {
            this.removeMessages(0);
            this.sendMessageDelayed(obtainMessage(0), delay);
        }
    }

    /**
     * Creates a new PongView within some context
     *
     * @param context
     * @param attrs
     */
    public PongView(Context context, AttributeSet attrs) {
        super(context, attrs);
        initPongView();
    }

    public PongView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initPongView();
    }

    /**
     * The main loop. Call this to update the game state.
     */
    public void update() {
        if (getHeight() == 0 || getWidth() == 0) {
            mRedrawHandler.sleep(1000 / mFramesPerSecond);
            return;
        }

        if (!initialized) {
            nextRound();
            newGame();
            initialized = true;
        }

        long now = System.currentTimeMillis();
        if (gameRunning() && mCurrentState != State.Stopped) {
            if (now - mLastFrame >= 1000 / mFramesPerSecond) {
                if (requestNewRound) {
                    nextRound();
                    requestNewRound = false;
                }
                doGameLogic();
            }
        }

        // We will take this much time off of the next update() call to normalize for
        // CPU time used updating the game state.

        if (mContinue) {
            long diff = System.currentTimeMillis() - now;
            mRedrawHandler.sleep(Math.max(0, (1000 / mFramesPerSecond) - diff));
        }
    }

    /**
     * All of the game's logic (per game iteration) is in this function.
     * Given some initial game state, it computes the next game state.
     */
    private void doGameLogic() {
        int px = mBallPosition.getX();
        int py = mBallPosition.getY();

        // Move the ball
        if (mBallCounter == 0) {
            mBallPosition.set(
                    normalizeBallX((int) (px + mBallSpeed * Math.cos(mBallAngle * Math.PI / 180.))),
                    py + mBallSpeed * Math.sin(mBallAngle * Math.PI / 180.)
            );
        } else {
            mBallCounter = Math.max(0, mBallCounter - 1);
        }

        mDX = mBallPosition.getX() - px;
        mDY = mBallPosition.getY() - py;

        // Shake it up if it appears to not be moving vertically
        if (py == mBallPosition.getY()) {
            //mBallAngle = RNG.nextInt(360);
            resetBallAngle();
        }

        // Move paddles with myo
        /*
        if (myoRedLeft) {
            int targetPaddlePosition = Math.min(getWidth(), Math.max(0, mRedPaddleRect.centerX() + myoMoveStep));

            movePaddleToward(mRedPaddleRect, 8 * mPaddleSpeed, targetPaddlePosition);
            System.out.println("doGameLogic: RED PADDLE MOVED LEFT");
        } else if (myoRedRight) {
            int targetPaddlePosition = Math.min(getWidth(), Math.max(0, mRedPaddleRect.centerX() - myoMoveStep));

            movePaddleToward(mRedPaddleRect, 8 * mPaddleSpeed, targetPaddlePosition);
            System.out.println("doGameLogic: RED PADDLE MOVED RIGHT");
        }

        if (myoBlueLeft) {
            int targetPaddlePosition = Math.min(getWidth(), Math.max(0, mBluePaddleRect.centerX() - myoMoveStep));

            movePaddleToward(mBluePaddleRect, 8 * mPaddleSpeed, targetPaddlePosition);
            System.out.println("doGameLogic: BLUE PADDLE MOVED LEFT");
        } else if (myoBlueRight) {
            int targetPaddlePosition = Math.min(getWidth(), Math.max(0, mBluePaddleRect.centerX() + myoMoveStep));

            movePaddleToward(mBluePaddleRect, 8 * mPaddleSpeed, targetPaddlePosition);
            System.out.println("doGameLogic: BLUE PADDLE MOVED RIGHT");
        }*/

        // move paddles
        updatePaddles();

        // See if all is lost
        if (mBallPosition.getY() >= getHeight()) {
            requestNewRound = true;
            mBlueLives = Math.max(0, mBlueLives - 1);

            if (mBlueLives != 0 || showTitle) {
                playSound(mMissTone);
            } else {
                playSound(mWinTone);
                redWins();
            }
        } else if (mBallPosition.getY() <= 0) {
            requestNewRound = true;
            mRedLives = Math.max(0, mRedLives - 1);
            if (mRedLives != 0 || showTitle) {
                playSound(mMissTone);
            } else {
                playSound(mWinTone);
                blueWins();
            }
        }

        // Handle bouncing off of a wall
        if (mBallPosition.getX() == BALL_RADIUS || mBallPosition.getX() == getWidth() - BALL_RADIUS) {
            bounceBallVertical();
            if (mBallPosition.getX() == BALL_RADIUS)
                mBallPosition.translate(1, 0);
            else
                mBallPosition.translate(-1, 0);
        }

        // Bouncing off the paddles
        if (mBallAngle >= 180 && ballCollides(mRedPaddleRect)) {
            bounceBallHorizontal();
            normalizeBallCollision(mRedPaddleRect);
            increaseDifficulty();

            hits1 ++;
        } else if (mBallAngle < 180 && ballCollides(mBluePaddleRect)) {
            bounceBallHorizontal();
            normalizeBallCollision(mBluePaddleRect);
            increaseDifficulty();

            hits2++;
        }
    }

    /**
     * Moves the paddle toward a specific x-coordinate without overshooting it.
     *
     * @param r,     the Rect object to move.
     * @param speed, the speed at which the paddle moves at maximum.
     * @param x,     the x-coordinate to move to.
     */
    private void movePaddleToward(Rect r, int speed, float x) {
        int dx = (int) Math.abs(r.centerX() - x);

        if (x < r.centerX()) {
            r.offset((dx > speed) ? -speed : -dx, 0);
        } else if (x > r.centerX()) {
            r.offset((dx > speed) ? speed : dx, 0);
        }
    }

    private void updatePaddles() {
        double w = getWidth();

        /*mBlueSpeed += Math.pow(-mBlueAcc.z(), 1.0) * mStrength - ((mBlueX / w) - 0.5) * mMagnet;
        mRedSpeed += Math.pow(mRedAcc.z(), 1.0) * mStrength - ((mRedX / w) - 0.5) * mMagnet;
        mBlueSpeed *= mFriction;
        mRedSpeed *= mFriction;
        mBlueX += mBlueSpeed;
        mRedX += mRedSpeed;*/

        double targetBlue = w / 2 - mBlueAcc.z() * w * 0.75;
        double targetRed = w / 2 + mRedAcc.z() * w * 0.75;
        mBlueX += (targetBlue - mBlueX) * 0.6;
        mRedX += (targetRed - mRedX) * 0.6;


        if (mBlueX < 0) {
            mBlueX = 0;
            mBlueSpeed = 0;
        } else if (mBlueX > w) {
            mBlueX = w;
            mBlueSpeed = 0;
        }
        if (mRedX < 0) {
            mRedX = 0;
            mRedSpeed = 0;
        } else if (mRedX > w) {
            mRedX = w;
            mRedSpeed = 0;
        }

        mBluePaddleRect.left = (int)mBlueX - PADDLE_WIDTH / 2;
        mBluePaddleRect.right = mBluePaddleRect.left + PADDLE_WIDTH;

        mRedPaddleRect.left = (int)mRedX - PADDLE_WIDTH / 2;
        mRedPaddleRect.right = mRedPaddleRect.left + PADDLE_WIDTH;
    }

    /**
     * Knocks up the framerate a bit to keep it difficult.
     */
    private void increaseDifficulty() {
        if (mFramesPerSecond < 100) {
            mFramesPerSecond += mFrameSkips;
            //mFrameSkips++;
        }
    }

    /**
     * Provides such faculties as normalizing where the ball
     * will be painted as well as varying the angle at which the ball will fly
     * when it bounces off the paddle.
     *
     * @param r
     */
    private void normalizeBallCollision(Rect r) {
        int x = mBallPosition.getX();
        int y = mBallPosition.getY();

        // Quit if the ball is outside the width of the paddle
        if (x < r.left || x > r.right) {
            return;
        }

        // Case if ball is above the paddle
        if (y < r.top) {
            mBallPosition.set(x, Math.min(y, r.top - BALL_RADIUS));
        } else if (y > r.bottom) {
            mBallPosition.set(x, Math.max(y, r.bottom + BALL_RADIUS));
        }


        /*int dA = 40 * Math.abs(x - r.centerX()) / Math.abs(r.left - r.centerX());
        if (mBallAngle > 180 && x < r.centerX() || mBallAngle < 180 && x > r.centerX()) {
            mBallAngle = safeRotate(mBallAngle, -dA);
        } else if (mBallAngle > 180 && x > r.centerX() || mBallAngle < 180 && x < r.centerX()) {
            mBallAngle = safeRotate(mBallAngle, dA);
        }*/
        double alpha = ((x - r.left) / (double)(r.width()) - 0.5) * 2;
        if (mBallAngle < 180) {
            mBallAngle = 90 - (int)(alpha * 60);
        } else {
            mBallAngle = 270 + (int)(alpha * 60);
        }
    }

    /**
     * Rotate the ball without extending beyond bounds which would create a case
     * where VY = 0.
     *
     * @param angle
     * @param da
     * @return
     */
    private int safeRotate(int angle, int da) {
        int dy, add;
        while (da != 0) {
            add = (da > 0) ? 1 : -1;
            angle += add;
            da -= add;

            dy = (int) (mBallSpeed * Math.sin(angle * Math.PI / 180));
            if (dy == 0) {
                return angle - add;
            }
        }
        return angle;
    }

    /**
     * Given it a coordinate, it transforms it into a proper x-coordinate for the ball.
     *
     * @param x, the x-coord to transform
     * @return
     */
    private int normalizeBallX(int x) {
        return Math.max(BALL_RADIUS, Math.min(x, getWidth() - BALL_RADIUS));
    }

    /**
     * Tells us if the ball collides with a rectangle.
     *
     * @param r, the rectangle
     * @return true if the ball is colliding, false if not
     */
    private boolean ballCollides(Rect r) {
        int x = mBallPosition.getX();
        int y = mBallPosition.getY();
        return y >= mRedPaddleRect.bottom && y <= mBluePaddleRect.bottom &&
                x >= r.left && x <= r.right &&
                y >= r.top - BALL_RADIUS && y <= r.bottom + BALL_RADIUS;
    }

    /**
     * Method bounces the ball across a vertical axis. Seriously it's that easy.
     * Math failed me when figuring this out so I guessed instead.
     */
    private void bounceBallVertical() {
        mBallAngle = (540 - mBallAngle) % 360;
        playSound(mWallHit);
    }

    /**
     * Bounce the ball off a horizontal axis.
     */
    private void bounceBallHorizontal() {
        // Amazingly enough...
        mBallAngle = (360 - mBallAngle) % 360;
        playSound(mPaddleHit);
    }

    /**
     * Set the state, start a new round, start the loop if needed.
     *
     * @param next, the next state
     */
    public void setMode(State next) {
        mCurrentState = next;
        nextRound();
        update();
    }

    /**
     * Set the paddles to their initial states and as well the ball.
     */
    private void initPongView() {
        setOnTouchListener(this);
        setOnKeyListener(this);
        setFocusable(true);
        resetPaddles();
        mBallPosition = new Point(getWidth() / 2, getHeight() / 2);

        mWallHit = loadSound(R.raw.ping_pong_8bit_beeep);
        mPaddleHit = loadSound(R.raw.ping_pong_8bit_peeeeeep);
        mMissTone = loadSound(R.raw.ping_pong_8bit_plop);
        mWinTone = loadSound(R.raw.ping_pong_8bit_beeep);

        // Grab the muted preference
        Context ctx = this.getContext();
        SharedPreferences settings = ctx.getSharedPreferences(PingPongActivity.DB_PREFS, 0);
        mMuted = settings.getBoolean(PingPongActivity.PREF_MUTED, mMuted);
    }

    /**
     * Reset the paddles/touchboxes/framespersecond/ballcounter for the next round.
     */
    private void nextRound() {
        mRedTouchBox = new Rect(0, 0, getWidth(), getHeight() / 8);
        mBlueTouchBox = new Rect(0, 7 * getHeight() / 8, getWidth(), getHeight());

        int min = Math.min(getWidth() / 4, getHeight() / 4);
        int xmid = getWidth() / 2;
        int ymid = getHeight() / 2;
        mPauseTouchBox = new Rect(xmid - min, ymid - min, xmid + min, ymid + min);

        realignPaddles();
        resetBall();
        mFramesPerSecond = 45;
        mBallCounter = 60;
    }

    private void realignPaddles() {
        mRedPaddleRect.top = mRedTouchBox.bottom + PADDING;
        mRedPaddleRect.bottom = mRedPaddleRect.top + PADDLE_THICKNESS;

        mBluePaddleRect.bottom = mBlueTouchBox.top - PADDING;
        mBluePaddleRect.top = mBluePaddleRect.bottom - PADDLE_THICKNESS;
    }

    /**
     * Reset paddles to an initial state.
     */
    private void resetPaddles() {
        mRedPaddleRect.top = PADDING;
        mRedPaddleRect.bottom = PADDING + PADDLE_THICKNESS;

        mBluePaddleRect.top = getHeight() - PADDING - PADDLE_THICKNESS;
        mBluePaddleRect.bottom = getHeight() - PADDING;

        mBluePaddleRect.left = mRedPaddleRect.left = getWidth() / 2 - PADDLE_WIDTH;
        mBluePaddleRect.right = mRedPaddleRect.right = getWidth() / 2 + PADDLE_WIDTH;

        mRedLastTouch = getWidth() / 2;
        mBlueLastTouch = getWidth() / 2;
    }

    /**
     * Reset ball to an initial state
     */
    private void resetBall() {
        mBallPosition.set(getWidth() / 2, getHeight() / 2);
        resetBallAngle();
        mBallCounter = 60;
    }

    private void resetBallAngle() {
        mBallAngle = 0;
        int direction = RNG.nextInt(4);
        int baseAngle = 60;
        int angleRand = 20;
        int randomAngle = RNG.nextInt(angleRand * 2) - angleRand;
        switch (direction) {
            case 0:
                mBallAngle = baseAngle + randomAngle;
                break;
            case 1:
                mBallAngle = 180 - baseAngle + randomAngle;
                break;
            case 2:
                mBallAngle = 180 + baseAngle + randomAngle;
                break;
            case 3:
                mBallAngle = 360 - baseAngle + randomAngle;
                break;
        }
    }

    /**
     * Use for keeping track of a position.
     *
     * @author pkilgo
     */
    class Point {
        private int x, y;

        Point() {
            x = 0;
            y = 0;
        }

        Point(int x, int y) {
            this.x = x;
            this.y = y;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public void set(double d, double e) {
            this.x = (int) d;
            this.y = (int) e;
        }

        public void translate(int i, int j) {
            this.x += i;
            this.y += j;
        }

        @Override
        public String toString() {
            return "Point: (" + x + ", " + y + ")";
        }
    }

    public void onSizeChanged(int w, int h, int ow, int oh) {
        mPaddleSpeed = Math.max(1, w / 160);
    }

    /**
     * Paints the game!
     */
    @Override
    public void onDraw(Canvas canvas) {
        long start = System.currentTimeMillis();

        super.onDraw(canvas);
        Context context = getContext();
        // Draw the paddles / touch boundaries
        mPaint.setStyle(Style.FILL);
        mPaint.setColor(Color.WHITE);

        canvas.drawRect(mRedPaddleRect, mPaint);

        // draw acc

        /*int x = getWidth() / 2;
        int y = getHeight() / 2;
        double size = 100;
        mPaint.setColor(Color.RED);
        canvas.drawLine(x, y, x + (int) (mRedAcc.z() * size), y + (int) (mRedAcc.y() * size), mPaint);
        mPaint.setColor(Color.BLUE);
        canvas.drawLine(x, y, x + (int) (mBlueAcc.z() * size), y + (int) (mBlueAcc.y() * size), mPaint);
        mPaint.setColor(Color.WHITE);*/


        if (gameRunning() && mRedIsPlayer && mCurrentState == State.Running)
            canvas.drawLine(mRedTouchBox.left, mRedTouchBox.bottom, mRedTouchBox.right, mRedTouchBox.bottom, mPaint);

        // Draw Blue's stuff
        mPaint.setColor(Color.WHITE);
        canvas.drawRect(mBluePaddleRect, mPaint);

        if (gameRunning() && mBlueIsPlayer && mCurrentState == State.Running)
            canvas.drawLine(mBlueTouchBox.left, mBlueTouchBox.top, mBlueTouchBox.right, mBlueTouchBox.top, mPaint);

        // Draw ball stuff
        mPaint.setStyle(Style.FILL);
        mPaint.setColor(Color.WHITE);

        if ((mBallCounter / 10) % 2 == 1 || mBallCounter == 0)
            canvas.drawCircle(mBallPosition.getX(), mBallPosition.getY(), BALL_RADIUS, mPaint);

        // If either is a not a player, blink and let them know they can join in!
        // This blinks with the ball.
        if (!showTitle && (mBallCounter / 10) % 2 == 1 && mBallCounter > 0) {
            String join = context.getString(R.string.join_in);
            int joinw = (int) mPaint.measureText(join);

            if (!mRedIsPlayer) {
                mPaint.setColor(Color.WHITE);
                canvas.drawText(join, getWidth() / 2 - joinw / 2, mRedTouchBox.centerY(), mPaint);
            }

            if (!mBlueIsPlayer) {
                mPaint.setColor(Color.WHITE);
                canvas.drawText(join, getWidth() / 2 - joinw / 2, mBlueTouchBox.centerY(), mPaint);
            }
        }

        // Show where the player can touch to pause the game
        if (!showTitle && (mBallCounter / 10) % 2 == 0 && mBallCounter > 0) {
            String pause = context.getString(R.string.pause);
            int pausew = (int) mPaint.measureText(pause);

            mPaint.setColor(Color.WHITE);
            mPaint.setStyle(Style.STROKE);
            canvas.drawRect(mPauseTouchBox, mPaint);
            canvas.drawText(pause, getWidth() / 2 - pausew / 2, getHeight() / 2, mPaint);
        }

        // Paint a PAUSED message
        if (gameRunning() && mCurrentState == State.Stopped) {
            String s = context.getString(R.string.paused);
            int width = (int) mPaint.measureText(s);
            int height = (int) (mPaint.ascent() + mPaint.descent());
            mPaint.setColor(Color.WHITE);
            canvas.drawText(s, getWidth() / 2 - width / 2, getHeight() / 2 - height / 2, mPaint);
        }

        // Draw a 'lives' counter
        if (!showTitle) {
            mPaint.setColor(Color.WHITE);
            mPaint.setStyle(Style.FILL_AND_STROKE);
            for (int i = 0; i < mRedLives; i++) {
                canvas.drawCircle(BALL_RADIUS + PADDING + i * (2 * BALL_RADIUS + PADDING),
                        PADDING + BALL_RADIUS,
                        BALL_RADIUS,
                        mPaint);
            }

            for (int i = 0; i < mBlueLives; i++) {
                canvas.drawCircle(BALL_RADIUS + PADDING + i * (2 * BALL_RADIUS + PADDING),
                        getHeight() - PADDING - BALL_RADIUS,
                        BALL_RADIUS,
                        mPaint);
            }
        }

        // Announce the winner!
        if (!gameRunning()) {
            mPaint.setColor(Color.WHITE);
            String s = "You both lost";

            if (mBlueLives == 0) {
                s = context.getString(R.string.red_wins);
                mPaint.setColor(Color.WHITE);
                mPaint.setTextSize(40);
            } else if (mRedLives == 0) {
                s = context.getString(R.string.blue_wins);
                mPaint.setColor(Color.WHITE);
                mPaint.setTextSize(40);
            }

            int width = (int) mPaint.measureText(s);
            int height = (int) (mPaint.ascent() + mPaint.descent());
            canvas.drawText(s, getWidth() / 2 - width / 2, getHeight() / 2 - height / 2, mPaint);
        }

        // Draw the Title text
        if (showTitle) {
            Bitmap image = BitmapFactory.decodeResource(context.getResources(), R.drawable.pong);

            canvas.drawBitmap(image, getWidth() / 2 - image.getWidth() / 2,
                    getHeight() / 2 - image.getHeight() / 2, mPaint);

        }

        long stop = System.currentTimeMillis();

//        Log.d(TAG, String.format("Draw took %d ms", stop - start));
    }

    private void redWins() {
        wins1++;

        // send scores
        System.out.println("-------------SCORE-------------");
        System.out.println(hits1);
        System.out.println(hits2);
        System.out.println(wins1);
        System.out.println(wins2);
        System.out.println("-------------------------------");

        new SendScoreTask().execute("");
    }

    private void blueWins() {
        wins2++;

        // send scores
        System.out.println("-------------SCORE-------------");
        System.out.println(hits1);
        System.out.println(hits2);
        System.out.println(wins1);
        System.out.println(wins2);
        System.out.println("-------------------------------");

        new SendScoreTask().execute("");
    }

    /**
     * Touching is the method of movement. Touching the touchscreen, that is.
     * A player can join in simply by touching where they would in a normal
     * game.
     */
    public boolean onTouch(View v, MotionEvent mo) {
        if (v != this || !gameRunning() || showTitle) return false;

        // We want to support multiple touch and single touch
        InputHandler handle = InputHandler.getInstance();

        // Loop through all the pointers that we detected and
        // process them as normal touch events.
        for (int i = 0; i < handle.getTouchCount(mo); i++) {
            int tx = (int) handle.getX(mo, i);
            int ty = (int) handle.getY(mo, i);

            // Bottom paddle moves when we are playing in one or two player mode and the touch
            // was in the lower quartile of the screen.
            if (mBlueIsPlayer && mBlueTouchBox.contains(tx, ty)) {
                mBlueLastTouch = tx;
            } else if (mRedIsPlayer && mRedTouchBox.contains(tx, ty)) {
                mRedLastTouch = tx;
            } else if (mo.getAction() == MotionEvent.ACTION_DOWN && mPauseTouchBox.contains(tx, ty)) {
                if (mCurrentState != State.Stopped) {
                    mLastState = mCurrentState;
                    mCurrentState = State.Stopped;
                } else {
                    mCurrentState = mLastState;
                    mLastState = State.Stopped;
                }
            }

            // In case a player wants to join in...
            if (mo.getAction() == MotionEvent.ACTION_DOWN) {
                if (!mBlueIsPlayer && mBlueTouchBox.contains(tx, ty)) {
                    mBlueIsPlayer = true;
                } else if (!mRedIsPlayer && mRedTouchBox.contains(tx, ty)) {
                    mRedIsPlayer = true;
                }
            }
        }

        return true;
    }

    @Override
    public boolean onTrackballEvent(MotionEvent event) {
        if (!gameRunning() || showTitle) return false;

        if (mBlueIsPlayer == false) {
            mBlueIsPlayer = true;
            mBlueLastTouch = mBluePaddleRect.centerX();
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_MOVE:
                mBlueLastTouch = Math.max(0, Math.min(getWidth(), mBlueLastTouch + SCROLL_SENSITIVITY * event.getX()));
                break;
        }

        return true;
    }

    /**
     * Reset the lives, paddles and the like for a new game.
     */
    public void newGame() {
        mRedLives = 5;
        mBlueLives = 5;
        mFrameSkips = 5;

        resetPaddles();
        nextRound();

        resumeLastState();
    }

    /**
     * This is kind of useless as well.
     */
    private void resumeLastState() {
        if (mLastState == State.Stopped && mCurrentState == State.Stopped) {
            mCurrentState = State.Running;
        } else if (mCurrentState != State.Stopped) {
            // Do nothing
        } else if (mLastState != State.Stopped) {
            mCurrentState = mLastState;
            mLastState = State.Stopped;
        }
    }

    public boolean gameRunning() {
        return showTitle || (mRedLives > 0 && mBlueLives > 0);
    }

    public void setShowTitle(boolean b) {
        showTitle = b;
    }

    public void pause() {
        if (!showTitle) {
            mLastState = mCurrentState;
            mCurrentState = State.Stopped;
        }
    }

    public boolean onKey(View v, int keyCode, KeyEvent event) {
        return false;
    }

    public void setPlayerControl(boolean red, boolean blue) {
        mRedIsPlayer = red;
        mBlueIsPlayer = blue;
    }

    public void onCompletion(MediaPlayer mp) {
        mp.seekTo(0);
    }

    public void resume() {
        mContinue = true;
        update();
    }

    public void stop() {
        mContinue = false;
    }

    public void toggleMuted() {
        this.setMuted(!mMuted);
    }

    public void setMuted(boolean b) {
        // Set the in-memory flag
        mMuted = b;

        // Grab a preference editor
        Context ctx = this.getContext();
        SharedPreferences settings = ctx.getSharedPreferences(PingPongActivity.DB_PREFS, 0);
        SharedPreferences.Editor editor = settings.edit();

        // Save the value
        editor.putBoolean(PingPongActivity.PREF_MUTED, b);
        editor.commit();

        // Output a toast to the user
        int rid = (mMuted) ? R.string.sound_disabled : R.string.sound_enabled;
        Toast.makeText(ctx, rid, Toast.LENGTH_SHORT).show();
    }

    /**
     * Put yer resources in year and we'll release em!
     */
    public void releaseResources() {
        mWallHit.release();
        mPaddleHit.release();
        mWinTone.release();
        mMissTone.release();
    }

    private MediaPlayer loadSound(int rid) {
        MediaPlayer mp = MediaPlayer.create(getContext(), rid);
        mp.setOnCompletionListener(this);
        return mp;
    }

    private void playSound(MediaPlayer mp) {
        if (mMuted == true) return;

        if (!mp.isPlaying()) {
            mp.setVolume(0.2f, 0.2f);
            mp.start();
        }
    }

    public void setRedAcc(Vector3 acc) {
        mRedAcc = acc;
    }

    public void setBlueAcc(Vector3 acc) {
        mBlueAcc = acc;
    }

    /*** MYO STATE STUFF ***/
    public void myoRedResetState() {
        myoRedLeft = false;
        myoRedRight = false;
        myoRedRest = false;
    }

    public void myoBlueResetState() {
        myoBlueLeft  = false;
        myoBlueRight = false;
        myoBlueRest  = false;
    }

    public void setMyoRedLeftState() {
        myoRedResetState();
        myoRedLeft = true;
    }

    public void setMyoRedRightState() {
        myoRedResetState();
        myoRedRight = true;
    }

    public void setMyoRedRestState() {
        myoRedResetState();
        myoRedRest = true;
    }

    public void setMyoBlueLeftState() {
        myoBlueResetState();
        myoBlueLeft = true;
    }

    public void setMyoBlueRightState() {
        myoBlueResetState();
        myoBlueRight = true;
    }

    public void setMyoBlueRestState() {
        myoBlueResetState();
        myoBlueRest = true;
    }

    public int getReflections1() {
        return hits1;
    }

    public int getReflections2() {
        return hits2;
    }

    public int getWins1() {
        return wins1;
    }

    public int getWins2() {
        return wins2;
    }

    private class SendScoreTask extends AsyncTask<String, Integer, String> {
        @Override
        protected String doInBackground(String... params) {
            URL url = null;
            HttpURLConnection conn = null;

            try {
                url = new URL("http://hackapong.herokuapp.com/scores.json");

                conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("charset", "utf-8");
                conn.setRequestProperty("uid1", "1");
                conn.setRequestProperty("uid2", "3");
                conn.setRequestProperty("reflections1", Integer.toString(hits1));
                conn.setRequestProperty("reflections2", Integer.toString(hits2));
                conn.setRequestProperty("wins1", Integer.toString(wins1));
                conn.setRequestProperty("wins2", Integer.toString(wins2));
                conn.setDoOutput(true);

                try {
                    DataOutputStream wr = new DataOutputStream(conn.getOutputStream());
                    wr.flush();
                    wr.close();
                    System.out.println("SENT");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }

            return "success";
        }
    }
}
