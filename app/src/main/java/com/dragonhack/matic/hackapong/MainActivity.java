package com.dragonhack.matic.hackapong;

import android.content.Intent;
import android.os.AsyncTask;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.ProtocolException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class MainActivity extends AppCompatActivity {

    protected String uid1;
    protected String pwd1;
    protected String uid2;
    protected String pwd2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void goToPingPong(View view) {
        Intent intent = new Intent(this, PingPongActivity.class);
        startActivity(intent);
    }

    public void login(View v){
        EditText uidEditText1 = (EditText) findViewById(R.id.uidEditText1);
        EditText pwdEditText1 = (EditText) findViewById(R.id.pwdEditText1);

        EditText uidEditText2 = (EditText) findViewById(R.id.uidEditText2);
        EditText pwdEditText2 = (EditText) findViewById(R.id.pwdEditText2);

        uid1 = uidEditText1.getText().toString();
        pwd1 = pwdEditText1.getText().toString();

        uid2 = uidEditText2.getText().toString();
        pwd2 = pwdEditText2.getText().toString();

        try {
            new CheckLoginTask().execute(new URL("http://hackapong.herokuapp.com/login.json"));
        } catch (MalformedURLException e) {
            e.printStackTrace();
        }
    }

    private class CheckLoginTask extends AsyncTask<URL, Integer, String> {

        @Override
        protected String doInBackground(URL... params) {
            URL url = null;
            HttpURLConnection conn = null;

            try {
                String urlParameters  = "username1=" + uid1 + "&password1=" + pwd1 + "&username2=" + uid2 + "&password2=" + pwd2;
                byte[] postData       = urlParameters.getBytes(StandardCharsets.UTF_8);
                int    postDataLength = postData.length;

                url = new URL("http://hackapong.herokuapp.com/login.json");

                conn = (HttpURLConnection) url.openConnection();

                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("charset", "utf-8");
                conn.setRequestProperty("Content-Length", Integer.toString(postDataLength));
                conn.setUseCaches(false);

                try {
                    DataOutputStream wr = new DataOutputStream(conn.getOutputStream());
                    wr.write(postData);
                    wr.flush();
                    wr.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (ProtocolException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return "success";
        }


        @Override
        protected void onPostExecute(String result){
            boolean success = true;
            if(result.length()==0){
                success = false;
            }
            if(success){
                JSONObject jObject = null;
                try {
                    jObject = new JSONObject(result);
                    System.out.print(jObject.toString());
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                Intent intent = new Intent(MainActivity.this, PingPongActivity.class);
                intent.putExtra("uid1", 3);
                intent.putExtra("uid2", 4);
                startActivity(intent);
            }else{
                //TODO: add some error test, ask a user to repeat loging in, clear edittexts
            }
        }
    }
}
