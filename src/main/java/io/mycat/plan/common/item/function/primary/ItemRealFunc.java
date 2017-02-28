package io.mycat.plan.common.item.function.primary;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;

import io.mycat.plan.common.item.Item;
import io.mycat.plan.common.item.function.ItemFunc;
import io.mycat.plan.common.time.MySQLTime;


public abstract class ItemRealFunc extends ItemFunc {

	public ItemRealFunc(List<Item> args) {
		super(args);
	}

	@Override
	public String valStr() {
		BigDecimal nr = valReal();
		if (nullValue)
			return null;
		return nr.toString();
	}

	@Override
	public BigInteger valInt() {
		return valReal().toBigInteger();
	}

	@Override
	public BigDecimal valDecimal() {
		BigDecimal nr = valReal();
		if (nullValue)
			return null;
		return nr;
	}

	@Override
	public ItemResult resultType() {
		return ItemResult.REAL_RESULT;
	}

	@Override
	public boolean getDate(MySQLTime ltime, long flags) {
		return getDateFromReal(ltime, flags);
	}

	@Override
	public boolean getTime(MySQLTime ltime) {
		return getTimeFromReal(ltime);
	}

	@Override
	public void fixLengthAndDec() {
		decimals = NOT_FIXED_DEC;
		maxLength = floatLength(decimals);
	}
}