package nxt;

import java.math.BigInteger;

public final class Genesis {


    public static final Long GENESIS_BLOCK_ID =-2514438845216930778L;
    public static final Long CREATOR_ID = 1739068987193023818L;

public static final byte[] CREATOR_PUBLIC_KEY = {
        18, 89, -20, 33, -45, 26, 48, -119, -115, 124, -47, 96, -97, -128, -39, 102,
        -117, 71, 120, -29, -39, 126, -108, 16, 68, -77, -97, 12, 68, -46, -27, 27
};
    public static final Long[] GENESIS_RECIPIENTS = {
            (new BigInteger("13674032869634748521")).longValue(),

    };

    public static final int[] GENESIS_AMOUNTS = {
        1000000000,
    };

    public static final byte[][] GENESIS_SIGNATURES = {
            {86,22,32,-123,26,53,35,-43,82,-71,123,54,-55,2,58,-44,123,-36,-48,-57,51,-59,98,-5,-16,99,-26,119,104,-117,-61,5,12,-105,-11,105,53,-48,-5,64,57,-12,76,71,-68,-91,-38,69,-77,96,15,87,53,7,30,5,-128,-80,79,-121,-111,-46,49,35}

 };


    public static final byte[] GENESIS_BLOCK_SIGNATURE = new byte[]{
            105, -44, 38, -60, -104, -73, 10, -58, -47, 103, -127, -128, 53, 101, 39, -63, -2, -32, 48, -83, 115, 47, -65, 118, 114, -62, 38, 109, 22, 106, 76, 8, -49, -113, -34, -76, 82, 79, -47, -76, -106, -69, -54, -85, 3, -6, 110, 103, 118, 15, 109, -92, 82, 37, 20, 2, 36, -112, 21, 72, 108, 72, 114, 17
    };


    private Genesis() {} // never

}
